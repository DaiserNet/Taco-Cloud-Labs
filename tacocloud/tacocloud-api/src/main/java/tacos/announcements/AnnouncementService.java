package tacos.announcements;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AnnouncementService {
  private final OpsAnnouncementRepository repository;
  private final ReactiveMongoTemplate mongo;
  private final Clock clock;
  private final int maxActive;
  private final int maxTextLength;
  private final int maxLifetimeHours;

  public AnnouncementService(OpsAnnouncementRepository repository,
      ReactiveMongoTemplate mongo, Clock clock,
      @Value("${tacocloud.announcements.max-active:20}") int maxActive,
      @Value("${tacocloud.announcements.max-text-length:500}") int maxTextLength,
      @Value("${tacocloud.announcements.max-lifetime-hours:168}")
          int maxLifetimeHours) {
    if (maxActive < 1 || maxActive > 100 || maxTextLength < 1
        || maxTextLength > 2000 || maxLifetimeHours < 1
        || maxLifetimeHours > 720) {
      throw new IllegalArgumentException("Invalid announcement limits");
    }
    this.repository = repository;
    this.mongo = mongo;
    this.clock = clock;
    this.maxActive = maxActive;
    this.maxTextLength = maxTextLength;
    this.maxLifetimeHours = maxLifetimeHours;
  }

  public Mono<AnnouncementResponse> create(AnnouncementRequest request,
      Authentication authentication) {
    return Mono.defer(() -> {
      requireAdmin(authentication);
      Instant now = clock.instant();
      validate(request, now);
      String id = UUID.randomUUID().toString();
      return cleanup(now).then(insert(id, 0, request, now,
          authentication.getName())).map(AnnouncementResponse::new);
    });
  }

  public Flux<AnnouncementResponse> list(Authentication authentication) {
    return Flux.defer(() -> {
      requireAdmin(authentication);
      Instant now = clock.instant();
      return cleanup(now).thenMany(repository
          .findByActiveTrueAndExpiresAtAfter(now,
              Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))
          .take(maxActive).map(AnnouncementResponse::new));
    });
  }

  public Mono<AnnouncementResponse> get(String id,
      Authentication authentication) {
    return Mono.defer(() -> {
      requireAdmin(authentication);
      return repository.findByIdAndActiveTrueAndExpiresAtAfter(
          id, clock.instant())
          .switchIfEmpty(Mono.error(new ResponseStatusException(
              HttpStatus.NOT_FOUND, "Announcement not found")))
          .map(AnnouncementResponse::new);
    });
  }

  public Mono<Void> delete(String id, Authentication authentication) {
    return Mono.defer(() -> {
      requireAdmin(authentication);
      Query query = Query.query(Criteria.where("_id").is(id)
          .and("active").is(true)
          .and("expiresAt").gt(clock.instant()));
      return mongo.findAndModify(query, Update.update("active", false),
          OpsAnnouncement.class)
          .switchIfEmpty(Mono.error(new ResponseStatusException(
              HttpStatus.NOT_FOUND, "Announcement not found")))
          .then();
    });
  }

  private Mono<OpsAnnouncement> insert(String id, int slot,
      AnnouncementRequest request, Instant now, String author) {
    if (slot >= maxActive) {
      return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
          "Active announcement limit reached"));
    }
    OpsAnnouncement announcement = new OpsAnnouncement(id, slot,
        request.getText().trim(), request.getSeverity(), now,
        request.getExpiresAt(), author);
    return mongo.insert(announcement)
        .onErrorResume(DuplicateKeyException.class,
            error -> insert(id, slot + 1, request, now, author));
  }

  private Mono<Void> cleanup(Instant now) {
    Query query = Query.query(new Criteria().orOperator(
        Criteria.where("expiresAt").lte(now),
        Criteria.where("active").is(false)));
    return mongo.remove(query, OpsAnnouncement.class).then();
  }

  private void validate(AnnouncementRequest request, Instant now) {
    if (request == null || request.getText() == null
        || request.getText().trim().isEmpty()
        || request.getText().length() > maxTextLength
        || request.getSeverity() == null
        || request.getExpiresAt() == null
        || !request.getExpiresAt().isAfter(now)
        || request.getExpiresAt().isAfter(
            now.plus(maxLifetimeHours, ChronoUnit.HOURS))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Invalid announcement");
    }
    for (int i = 0; i < request.getText().length(); i++) {
      if (Character.isISOControl(request.getText().charAt(i))) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Announcement contains control characters");
      }
    }
  }

  private void requireAdmin(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()
        || !authentication.getAuthorities().stream().anyMatch(authority ->
            "ROLE_ADMIN".equals(authority.getAuthority()))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }
}
