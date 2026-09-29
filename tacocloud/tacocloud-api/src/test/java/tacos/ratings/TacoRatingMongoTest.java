package tacos.ratings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.bson.Document;
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
import tacos.Taco;
import tacos.TacoRating;
import tacos.User;
import tacos.api.dto.TacoRankingResponse;
import tacos.data.TacoRatingRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@SpringBootTest(classes = TacoRatingMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.config.name=tc22-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc22-test",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.mongodb.embedded.version=3.5.5",
        "tacocloud.ratings.min-votes=2",
        "tacocloud.ratings.max-top-limit=10"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TacoRatingMongoTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = TacoRatingRepository.class)
  @Import(TacoRatingService.class)
  static class TestApplication {
  }

  @Autowired private TacoRatingService service;
  @Autowired private TacoRatingRepository ratingRepo;
  @Autowired private TacoRepository tacoRepo;
  @Autowired private UserRepository userRepo;
  @Autowired private ReactiveMongoTemplate mongo;

  private String aliceId;
  private String bobId;
  private String carolId;
  private String danId;

  @BeforeEach
  void setUp() {
    StepVerifier.create(ratingRepo.deleteAll().then(tacoRepo.deleteAll())
        .then(userRepo.deleteAll())
        .then(userRepo.save(user("alice")))
        .doOnNext(user -> aliceId = user.getId())
        .then(userRepo.save(user("bob")))
        .doOnNext(user -> bobId = user.getId())
        .then(userRepo.save(user("carol")))
        .doOnNext(user -> carolId = user.getId())
        .then(userRepo.save(user("dan")))
        .doOnNext(user -> danId = user.getId())
        .thenMany(Flux.fromIterable(Arrays.asList(
            taco("A", true), taco("B", true), taco("C", true),
            taco("D", true), taco("E", false), taco("F", true),
            taco("G", true))).concatMap(tacoRepo::save)).then())
        .verifyComplete();
  }

  @Test
  void shouldUpsertOneVotePerUserAndKeepOtherVotes() {
    StepVerifier.create(service.rate("A", 5, auth("alice"))
        .then(service.rate("A", 3, auth("bob")))
        .then(service.rate("A", 4, auth("alice"))))
        .verifyComplete();

    StepVerifier.create(ratingRepo.countByTacoId("A"))
        .expectNext(2L).verifyComplete();
    StepVerifier.create(ratingRepo.findByUserIdAndTacoId(aliceId, "A"))
        .assertNext(rating -> assertEquals(4, rating.getScore()))
        .verifyComplete();
    StepVerifier.create(ratingRepo.findByUserIdAndTacoId(bobId, "A"))
        .assertNext(rating -> assertEquals(3, rating.getScore()))
        .verifyComplete();
  }

  @Test
  void shouldEnforceUniqueIndexDuringConcurrentUpsert() {
    StepVerifier.create(mongo.indexOps(TacoRating.class).getIndexInfo()
        .map(index -> index.getName()).collectList())
        .assertNext(names -> assertTrue(names.contains("rating_user_taco_unique")))
        .verifyComplete();
    StepVerifier.create(Mono.when(service.rate("A", 5, auth("alice")),
        service.rate("A", 5, auth("alice")))).verifyComplete();
    StepVerifier.create(ratingRepo.countByTacoId("A"))
        .expectNext(1L).verifyComplete();
    StepVerifier.create(ratingRepo.save(rating(aliceId, "A", 5)))
        .expectError(DuplicateKeyException.class).verify();
  }

  @Test
  void shouldRejectInvalidScoreUnknownTacoAndUnpublishedTaco() {
    assertStatus(service.rate("A", 0, auth("alice")), HttpStatus.BAD_REQUEST);
    assertStatus(service.rate("A", 6, auth("alice")), HttpStatus.BAD_REQUEST);
    assertStatus(service.rate("MISSING", 5, auth("alice")), HttpStatus.NOT_FOUND);
    assertStatus(service.rate("E", 5, auth("alice")), HttpStatus.NOT_FOUND);
    StepVerifier.create(ratingRepo.count()).expectNext(0L).verifyComplete();
  }

  @Test
  void shouldRankInMongoByAverageVotesAndIdWithDistribution() {
    List<TacoRating> votes = Arrays.asList(
        rating(aliceId, "A", 5), rating(bobId, "A", 4),
        rating(aliceId, "B", 4), rating(bobId, "B", 5),
        rating(aliceId, "C", 5),
        rating(aliceId, "D", 5), rating(bobId, "D", 4),
        rating(carolId, "D", 5), rating(danId, "D", 4),
        rating(aliceId, "E", 5), rating(bobId, "E", 5),
        rating(carolId, "E", 5),
        rating(aliceId, "F", 5), rating(bobId, "F", 5),
        rating(aliceId, "G", 5), rating(bobId, "G", 4),
        rating(carolId, "G", 4));
    StepVerifier.create(Flux.fromIterable(votes).concatMap(ratingRepo::save)
        .then(tacoRepo.deleteById("F"))).verifyComplete();

    StepVerifier.create(service.top(10).collectList())
        .assertNext(top -> {
          assertEquals(Arrays.asList("D", "A", "B", "G"), ids(top));
          assertEquals(new BigDecimal("4.50"), top.get(0).getAverage());
          assertEquals(4L, top.get(0).getCount());
          assertEquals(Arrays.asList(0L, 0L, 0L, 2L, 2L),
              top.get(0).getDistribution());
          assertEquals(new BigDecimal("4.33"), top.get(3).getAverage());
          assertEquals(Arrays.asList(0L, 0L, 0L, 2L, 1L),
              top.get(3).getDistribution());
        }).verifyComplete();
    StepVerifier.create(service.top(2).collectList())
        .assertNext(top -> assertEquals(Arrays.asList("D", "A"), ids(top)))
        .verifyComplete();
  }

  @Test
  void shouldRejectInvalidLimitAndAcceptLegacyPublishedTaco() {
    StepVerifier.create(service.top(0))
        .expectError(ResponseStatusException.class).verify();
    StepVerifier.create(service.top(11))
        .expectError(ResponseStatusException.class).verify();

    Document legacy = new Document("_id", "LEGACY")
        .append("name", "Legacy taco").append("createdAt", new Date());
    StepVerifier.create(mongo.insert(legacy, mongo.getCollectionName(Taco.class))
        .then(tacoRepo.findById("LEGACY")))
        .assertNext(taco -> assertTrue(taco.isPublished()))
        .verifyComplete();
    StepVerifier.create(service.rate("LEGACY", 5, auth("alice")))
        .verifyComplete();
  }

  private void assertStatus(Mono<Void> action, HttpStatus status) {
    StepVerifier.create(action).expectErrorSatisfies(error ->
        assertEquals(status, ((ResponseStatusException) error).getStatus()))
        .verify();
  }

  private List<String> ids(List<TacoRankingResponse> top) {
    return top.stream().map(TacoRankingResponse::getTacoId)
        .collect(Collectors.toList());
  }

  private Authentication auth(String username) {
    return new UsernamePasswordAuthenticationToken(username, "unused",
        AuthorityUtils.createAuthorityList("ROLE_USER"));
  }

  private User user(String username) {
    return new User(username, "encoded", username, "Street", "City", "ST",
        "12345", "5551234", username + "@example.com",
        Collections.singleton("USER"));
  }

  private Taco taco(String id, boolean published) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Taco " + id);
    taco.setPublished(published);
    return taco;
  }

  private TacoRating rating(String userId, String tacoId, int score) {
    TacoRating rating = new TacoRating();
    rating.setUserId(userId);
    rating.setTacoId(tacoId);
    rating.setScore(score);
    return rating;
  }
}
