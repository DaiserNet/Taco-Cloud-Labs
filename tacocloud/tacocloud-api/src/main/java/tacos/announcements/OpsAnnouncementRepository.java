package tacos.announcements;

import java.time.Instant;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RepositoryRestResource(exported = false)
public interface OpsAnnouncementRepository
    extends ReactiveMongoRepository<OpsAnnouncement, String> {
  Flux<OpsAnnouncement> findByActiveTrueAndExpiresAtAfter(Instant now, Sort sort);
  Mono<OpsAnnouncement> findByIdAndActiveTrueAndExpiresAtAfter(
      String id, Instant now);
}
