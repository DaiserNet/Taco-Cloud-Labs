package tacos.favorites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Favorite;
import tacos.Taco;
import tacos.User;
import tacos.data.FavoriteRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@SpringBootTest(classes = FavoriteServiceMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.config.name=tc21-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc21-test",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.mongodb.embedded.version=3.5.5"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FavoriteServiceMongoTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = FavoriteRepository.class)
  @Import(FavoriteService.class)
  static class TestApplication {
  }

  @Autowired private FavoriteService service;
  @Autowired private FavoriteRepository favoriteRepo;
  @Autowired private TacoRepository tacoRepo;
  @Autowired private UserRepository userRepo;
  @Autowired private ReactiveMongoTemplate mongo;

  private String aliceId;
  private String bobId;

  @BeforeEach
  void setUp() {
    StepVerifier.create(favoriteRepo.deleteAll().then(tacoRepo.deleteAll())
        .then(userRepo.deleteAll())
        .then(userRepo.save(user("alice")))
        .doOnNext(user -> aliceId = user.getId())
        .then(userRepo.save(user("bob")))
        .doOnNext(user -> bobId = user.getId())
        .thenMany(Flux.fromIterable(Arrays.asList(
            taco("A"), taco("B"), taco("C")))
            .concatMap(tacoRepo::save)).then()).verifyComplete();
  }

  @Test
  void shouldCreateUniqueIndexAndKeepOneFavoriteUnderConcurrentPut() {
    StepVerifier.create(mongo.indexOps(Favorite.class).getIndexInfo()
        .map(index -> index.getName()).collectList())
        .assertNext(names -> assertTrue(names.contains("favorite_user_taco_unique")))
        .verifyComplete();

    StepVerifier.create(Mono.when(service.add("A", alice()),
        service.add("A", alice()))).verifyComplete();
    StepVerifier.create(favoriteRepo.countByUserId(aliceId))
        .expectNext(1L).verifyComplete();
    StepVerifier.create(favoriteRepo.save(new Favorite(aliceId, "A")))
        .expectError(DuplicateKeyException.class).verify();
  }

  @Test
  void shouldKeepUsersIsolatedAndReturnStablePages() {
    StepVerifier.create(service.add("C", alice())
        .then(service.add("A", alice()))
        .then(service.add("B", alice()))
        .then(service.add("B", bob()))).verifyComplete();

    StepVerifier.create(service.list(0, 2, alice()))
        .assertNext(page -> {
          assertEquals(Arrays.asList("A", "B"), page.getContent().stream()
              .map(item -> item.getTacoId()).collect(Collectors.toList()));
          assertEquals(3, page.getTotalElements());
          assertEquals(2, page.getTotalPages());
        }).verifyComplete();
    StepVerifier.create(service.list(1, 2, alice()))
        .assertNext(page -> assertEquals("C",
            page.getContent().get(0).getTacoId())).verifyComplete();
    StepVerifier.create(service.list(0, 2, bob()))
        .assertNext(page -> {
          assertEquals(1, page.getTotalElements());
          assertEquals("B", page.getContent().get(0).getTacoId());
        }).verifyComplete();
    assertFalse(aliceId.equals(bobId));
  }

  @Test
  void shouldMakeRepeatedDeleteIdempotentWithoutTouchingOtherUsers() {
    StepVerifier.create(service.add("A", alice())
        .then(service.add("A", bob()))
        .then(service.remove("A", alice()))
        .then(service.remove("A", alice()))).verifyComplete();

    StepVerifier.create(favoriteRepo.countByUserId(aliceId))
        .expectNext(0L).verifyComplete();
    StepVerifier.create(favoriteRepo.countByUserId(bobId))
        .expectNext(1L).verifyComplete();
  }

  @Test
  void shouldRejectUnknownTacoAndInvalidPaging() {
    StepVerifier.create(service.add("MISSING", alice()))
        .expectErrorSatisfies(error -> assertEquals(HttpStatus.NOT_FOUND,
            ((ResponseStatusException) error).getStatus())).verify();
    StepVerifier.create(favoriteRepo.countByUserId(aliceId))
        .expectNext(0L).verifyComplete();
    StepVerifier.create(service.list(-1, 20, alice()))
        .expectError(ResponseStatusException.class).verify();
    StepVerifier.create(service.list(0, 51, alice()))
        .expectError(ResponseStatusException.class).verify();
  }

  @Test
  void shouldMarkMissingTacoAsOrphanAndRestoreItIfTacoReturns() {
    StepVerifier.create(service.add("A", alice())
        .then(tacoRepo.deleteById("A"))).verifyComplete();

    StepVerifier.create(service.list(0, 20, alice()))
        .assertNext(page -> {
          assertEquals(1, page.getTotalElements());
          assertTrue(page.getContent().get(0).isOrphaned());
          assertEquals("A", page.getContent().get(0).getTacoId());
        }).verifyComplete();
    StepVerifier.create(favoriteRepo.findByUserIdAndTacoId(aliceId, "A"))
        .assertNext(favorite -> assertTrue(favorite.isOrphaned()))
        .verifyComplete();

    StepVerifier.create(tacoRepo.save(taco("A"))
        .then(service.list(0, 20, alice())))
        .assertNext(page -> {
          assertFalse(page.getContent().get(0).isOrphaned());
          assertEquals("Taco A", page.getContent().get(0).getTacoName());
        }).verifyComplete();
    StepVerifier.create(favoriteRepo.findByUserIdAndTacoId(aliceId, "A"))
        .assertNext(favorite -> assertFalse(favorite.isOrphaned()))
        .verifyComplete();
  }

  private Authentication alice() {
    return authentication("alice");
  }

  private Authentication bob() {
    return authentication("bob");
  }

  private Authentication authentication(String username) {
    return new UsernamePasswordAuthenticationToken(username, "unused",
        AuthorityUtils.createAuthorityList("ROLE_USER"));
  }

  private User user(String username) {
    return new User(username, "encoded", username, "Street", "City", "ST",
        "12345", "5551234", username + "@example.com",
        Collections.singleton("USER"));
  }

  private Taco taco(String id) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Taco " + id);
    return taco;
  }
}
