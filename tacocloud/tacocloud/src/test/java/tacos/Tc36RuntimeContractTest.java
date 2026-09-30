package tacos;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.embedded.EmbeddedMongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.test.StepVerifier;
import tacos.data.IngredientRepository;
import tacos.data.UserRepository;

@Testcontainers(disabledWithoutDocker = true)
@ImportAutoConfiguration(exclude = EmbeddedMongoAutoConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.boot.admin.client.enabled=false"})
class Tc36RuntimeContractTest {
  private static final String DATABASE = "tc36_http_"
      + UUID.randomUUID().toString().replace("-", "");

  @Container static final MongoDBContainer MONGO =
      new MongoDBContainer("mongo:4.4.6");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri",
        () -> MONGO.getReplicaSetUrl(DATABASE));
  }

  @Autowired private WebTestClient http;
  @Autowired private ReactiveMongoTemplate mongo;
  @Autowired private IngredientRepository ingredients;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;

  private String userName;
  private String userId;
  private String adminName;
  private String adminId;
  private String ingredientId;

  @BeforeEach
  void seedSyntheticUsers() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    userName = "tc36_user_" + suffix;
    adminName = "tc36_admin_" + suffix;
    ingredientId = "TC36_" + suffix;
    StepVerifier.create(users.save(user(userName, "USER")))
        .assertNext(user -> userId = user.getId())
        .verifyComplete();
    StepVerifier.create(users.save(user(adminName, "ADMIN")))
        .assertNext(admin -> adminId = admin.getId())
        .verifyComplete();
  }

  @AfterEach
  void removeSyntheticData() {
    StepVerifier.create(mongo.remove(Query.query(Criteria.where("_id")
            .is(ingredientId)), Ingredient.class)
        .then(users.deleteById(userId))
        .then(users.deleteById(adminId)))
        .verifyComplete();
  }

  @Test
  void publicCatalogUsesRealMvcAndContainerMongo() {
    StepVerifier.create(ingredients.save(new Ingredient(ingredientId,
            "Synthetic vegetable", Ingredient.Type.VEGGIES)))
        .expectNextCount(1).verifyComplete();
    http.get().uri("/api/v1/ingredients/{id}", ingredientId)
        .exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.id").isEqualTo(ingredientId);
  }

  @Test
  void userCannotReachAdminOrUndeclaredRoutes() {
    http.get().uri("/api/v1/admin/orders")
        .headers(headers -> headers.setBasicAuth(userName, "tc36-password"))
        .exchange().expectStatus().isForbidden();
    http.get().uri("/api/v1/undeclared-tc36")
        .headers(headers -> headers.setBasicAuth(userName, "tc36-password"))
        .exchange().expectStatus().isForbidden();
  }

  @Test
  void csrfBlocksWriteWithoutTokenAndAllowsValidatedControllerCall() {
    String request = "{\"name\":\"Invalid taco\",\"ingredients\":[]}";
    http.post().uri("/api/v1/tacos/validate")
        .headers(headers -> headers.setBasicAuth(userName, "tc36-password"))
        .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
        .exchange().expectStatus().isUnauthorized();

    String token = csrfToken();
    http.post().uri("/api/v1/tacos/validate")
        .headers(headers -> headers.setBasicAuth(userName, "tc36-password"))
        .cookie("XSRF-TOKEN", token).header("X-XSRF-TOKEN", token)
        .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
        .exchange().expectStatus().isOk().expectBody()
        .jsonPath("$.valid").isEqualTo(false)
        .jsonPath("$.violations[0].code").exists();
  }

  @Test
  void adminCreateAndDeleteChangeRealMongoState() {
    String token = csrfToken();
    String request = "{\"id\":\"" + ingredientId
        + "\",\"name\":\"Synthetic vegetable\",\"type\":\"VEGGIES\"}";
    http.post().uri("/api/v1/ingredients")
        .headers(headers -> headers.setBasicAuth(adminName, "tc36-password"))
        .cookie("XSRF-TOKEN", token).header("X-XSRF-TOKEN", token)
        .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
        .exchange().expectStatus().isCreated()
        .expectHeader().valueMatches("Location", ".*/api/v1/ingredients/" + ingredientId)
        .expectBody().jsonPath("$.id").isEqualTo(ingredientId);
    http.get().uri("/api/v1/ingredients/{id}", ingredientId)
        .exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.name").isEqualTo("Synthetic vegetable");
    http.delete().uri("/api/v1/ingredients/{id}", ingredientId)
        .headers(headers -> headers.setBasicAuth(adminName, "tc36-password"))
        .cookie("XSRF-TOKEN", token).header("X-XSRF-TOKEN", token)
        .exchange().expectStatus().isNoContent();
    http.get().uri("/api/v1/ingredients/{id}", ingredientId)
        .exchange().expectStatus().isNotFound();
  }

  private String csrfToken() {
    String token = http.get().uri("/").exchange().expectStatus().isOk()
        .returnResult(Void.class).getResponseCookies()
        .getFirst("XSRF-TOKEN").getValue();
    assertNotNull(token);
    return token;
  }

  private User user(String name, String role) {
    return new User(name, encoder.encode("tc36-password"), "TC36 Test",
        "Synthetic Street", "Test City", "TX", "00000", "000-000-0000",
        name + "@example.invalid", Collections.singleton(role));
  }
}
