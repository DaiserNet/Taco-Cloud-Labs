package tacos.announcements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tacos.pricing.CouponClockConfiguration;

@SpringBootTest(classes = AnnouncementMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.config.name=tc33-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc33-test",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.mongodb.embedded.version=3.5.5"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnnouncementMongoTest {
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = OpsAnnouncementRepository.class)
  @Import({AnnouncementService.class, CouponClockConfiguration.class})
  static class TestApplication { }

  @Autowired private OpsAnnouncementRepository repository;
  @Autowired private ReactiveMongoTemplate mongo;

  @BeforeEach
  void clear() {
    StepVerifier.create(repository.deleteAll()).verifyComplete();
  }

  @Test
  void shouldKeepRepositoryOutsideDataRest() {
    assertFalse(OpsAnnouncementRepository.class
        .getAnnotation(RepositoryRestResource.class).exported());
  }

  @Test
  void shouldKeepAnnouncementsWhenServiceIsRecreated() {
    AnnouncementService first = service(20, NOW);
    AnnouncementRequest request = request("Maintenance", NOW.plusSeconds(3600));
    StepVerifier.create(first.create(request, admin())
        .flatMap(created -> {
          assertEquals("Maintenance", created.getText());
          assertTrue(created.isActive());
          AnnouncementService restarted = service(20, NOW);
          return restarted.list(admin()).single()
              .zipWith(repository.findAll().single())
              .doOnNext(pair -> {
                assertEquals(created.getId(), pair.getT1().getId());
                assertEquals("admin", pair.getT2().getCreatedBy());
              }).thenReturn(created);
        })).expectNextCount(1).verifyComplete();
  }

  @Test
  void shouldBoundConcurrentWritesWithUniqueMongoSlots() {
    AnnouncementService limited = service(2, NOW);
    StepVerifier.create(Flux.range(0, 8)
        .flatMap(number -> limited.create(request("Notice " + number,
                NOW.plusSeconds(3600)), admin())
            .subscribeOn(Schedulers.parallel())
            .onErrorResume(ResponseStatusException.class, error -> {
              assertEquals(HttpStatus.CONFLICT, error.getStatus());
              return Mono.empty();
            }), 8)
        .collectList())
        .assertNext(created -> {
          assertEquals(2, created.size());
          assertNotEquals(created.get(0).getId(), created.get(1).getId());
        }).verifyComplete();
    StepVerifier.create(limited.list(admin()).collectList())
        .assertNext(items -> assertEquals(2, items.size()))
        .verifyComplete();
  }

  @Test
  void shouldHideAndCleanExpiredAnnouncementsUsingClock() {
    AnnouncementService first = service(20, NOW);
    StepVerifier.create(first.create(request("Temporary",
        NOW.plusSeconds(3600)), admin())).expectNextCount(1).verifyComplete();

    AnnouncementService later = service(20, NOW.plusSeconds(7200));
    StepVerifier.create(later.list(admin())).verifyComplete();
    StepVerifier.create(repository.count()).expectNext(0L).verifyComplete();
  }

  @Test
  void shouldDeleteOnlyTheRequestedStableIdAndFreeItsSlot() {
    AnnouncementService limited = service(2, NOW);
    StepVerifier.create(limited.create(request("First",
        NOW.plusSeconds(3600)), admin()).flatMap(first ->
        limited.create(request("Second", NOW.plusSeconds(3600)), admin())
            .flatMap(second -> limited.delete(first.getId(), admin())
                .then(limited.get(first.getId(), admin()).materialize())
                .doOnNext(signal -> {
                  assertTrue(signal.isOnError());
                  assertEquals(HttpStatus.NOT_FOUND,
                      ((ResponseStatusException) signal.getThrowable())
                          .getStatus());
                })
                .then(limited.create(request("Third", NOW.plusSeconds(3600)),
                    admin()))
                .then(limited.list(admin()).collectList())
                .doOnNext(items -> {
                  assertEquals(2, items.size());
                  assertTrue(items.stream().anyMatch(item ->
                      second.getId().equals(item.getId())));
                  assertTrue(items.stream().noneMatch(item ->
                      first.getId().equals(item.getId())));
                })))).expectNextCount(1).verifyComplete();
  }

  @Test
  void shouldRejectBlankControlCharactersLengthAndInvalidExpiry() {
    AnnouncementService limited = new AnnouncementService(repository, mongo,
        Clock.fixed(NOW, ZoneOffset.UTC), 20, 12, 2);
    String[] texts = {" ", "bad\ntext", "1234567890123"};
    for (String text : texts) {
      StepVerifier.create(limited.create(request(text,
          NOW.plusSeconds(3600)), admin()))
          .expectErrorMatches(error -> error instanceof ResponseStatusException
              && ((ResponseStatusException) error).getStatus()
                  == HttpStatus.BAD_REQUEST)
          .verify();
    }
    StepVerifier.create(limited.create(request("Past", NOW), admin()))
        .expectError(ResponseStatusException.class).verify();
    StepVerifier.create(limited.create(request("Too long-lived",
        NOW.plusSeconds(10800)), admin()))
        .expectError(ResponseStatusException.class).verify();
    AnnouncementRequest noSeverity = request("No severity", NOW.plusSeconds(3600));
    noSeverity.setSeverity(null);
    StepVerifier.create(limited.create(noSeverity, admin()))
        .expectError(ResponseStatusException.class).verify();
    StepVerifier.create(repository.count()).expectNext(0L).verifyComplete();
  }

  private AnnouncementService service(int maxActive, Instant now) {
    return new AnnouncementService(repository, mongo,
        Clock.fixed(now, ZoneOffset.UTC), maxActive, 500, 168);
  }

  private AnnouncementRequest request(String text, Instant expiresAt) {
    AnnouncementRequest request = new AnnouncementRequest();
    request.setText(text);
    request.setSeverity(OpsAnnouncement.Severity.WARNING);
    request.setExpiresAt(expiresAt);
    return request;
  }

  private Authentication admin() {
    return new UsernamePasswordAuthenticationToken("admin", "unused",
        AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
  }
}
