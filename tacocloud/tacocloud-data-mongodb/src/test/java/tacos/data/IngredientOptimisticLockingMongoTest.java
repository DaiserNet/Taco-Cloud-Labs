package tacos.data;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.test.annotation.DirtiesContext;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;

@SpringBootTest(
    classes = IngredientOptimisticLockingMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.config.name=tc13-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc13-test",
        "spring.mongodb.embedded.version=3.5.5",
        "logging.level.org.springframework.boot.autoconfigure.mongo.embedded=OFF"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IngredientOptimisticLockingMongoTest {

  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = IngredientRepository.class)
  static class TestApplication {
  }

  @Autowired
  private IngredientRepository ingredientRepo;

  @BeforeEach
  void cleanCollection() {
    StepVerifier.create(ingredientRepo.deleteAll()).verifyComplete();
  }

  @Test
  void shouldRejectSecondSaveFromStaleIngredientVersion() {
    Ingredient ingredient = new Ingredient("FLTO", "Flour Tortilla",
        Ingredient.Type.WRAP, new BigDecimal("0.50"), true, 10, 2);

    Mono<Ingredient> staleSave = ingredientRepo.save(ingredient)
        .then(Mono.zip(
            ingredientRepo.findById("FLTO"),
            ingredientRepo.findById("FLTO")))
        .flatMap(copies -> {
          Ingredient first = copies.getT1();
          Ingredient stale = copies.getT2();
          first.setStockOnHand(9);
          stale.setUnitPrice(new BigDecimal("0.75"));
          return ingredientRepo.save(first)
              .then(ingredientRepo.save(stale));
        });

    StepVerifier.create(staleSave)
        .expectError(OptimisticLockingFailureException.class)
        .verify(TEST_TIMEOUT);
  }
}
