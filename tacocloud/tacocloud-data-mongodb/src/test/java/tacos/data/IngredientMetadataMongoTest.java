package tacos.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.test.annotation.DirtiesContext;

import reactor.test.StepVerifier;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.SpiceLevel;

@SpringBootTest(
    classes = IngredientMetadataMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.config.name=tc17-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc17-test",
        "spring.mongodb.embedded.version=3.5.5",
        "logging.level.org.springframework.boot.autoconfigure.mongo.embedded=OFF"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IngredientMetadataMongoTest {

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = IngredientRepository.class)
  static class TestApplication {
  }

  @Autowired
  private IngredientRepository ingredientRepo;

  @Test
  void shouldPersistTypedDietaryMetadataInMongo() {
    Ingredient ingredient = new Ingredient("TEST-TC17", "Sesame sauce",
        Ingredient.Type.SAUCE);
    ingredient.setDietaryTags(EnumSet.of(DietaryTag.VEGAN,
        DietaryTag.GLUTEN_FREE));
    ingredient.setAllergens(EnumSet.of(Allergen.SESAME, Allergen.SOY));
    ingredient.setSpiceLevel(SpiceLevel.HOT);

    StepVerifier.create(ingredientRepo.save(ingredient)
            .then(ingredientRepo.findById("TEST-TC17")))
        .assertNext(saved -> {
          assertEquals(ingredient.getDietaryTags(), saved.getDietaryTags());
          assertEquals(ingredient.getAllergens(), saved.getAllergens());
          assertEquals(SpiceLevel.HOT, saved.getSpiceLevel());
        })
        .verifyComplete();
  }
}
