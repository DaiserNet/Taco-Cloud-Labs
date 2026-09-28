package tacos.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.api.dto.TacoClassification;
import tacos.data.IngredientRepository;

class TacoClassificationServiceTest {

  private final IngredientRepository repo = mock(IngredientRepository.class);
  private final TacoClassificationService service =
      new TacoClassificationService(repo);

  @Test
  void shouldRequireEveryIngredientForDietaryTags() {
    Ingredient plant = ingredient("PLANT", SpiceLevel.NONE,
        EnumSet.of(DietaryTag.VEGAN, DietaryTag.GLUTEN_FREE),
        EnumSet.noneOf(Allergen.class));
    Ingredient dairy = ingredient("DAIRY", SpiceLevel.NONE,
        EnumSet.of(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
        EnumSet.of(Allergen.MILK));

    TacoClassification allPlant = service.classify(taco(plant));
    assertTrue(allPlant.getDietaryTags().contains(DietaryTag.VEGAN));
    assertTrue(allPlant.getDietaryTags().contains(DietaryTag.VEGETARIAN));
    assertTrue(allPlant.getDietaryTags().contains(DietaryTag.GLUTEN_FREE));

    TacoClassification result = service.classify(taco(plant, dairy));

    assertFalse(result.getDietaryTags().contains(DietaryTag.VEGAN));
    assertTrue(result.getDietaryTags().contains(DietaryTag.VEGETARIAN));
    assertTrue(result.getDietaryTags().contains(DietaryTag.GLUTEN_FREE));
    dairy.setDietaryTags(EnumSet.of(DietaryTag.VEGETARIAN));
    assertFalse(service.classify(taco(plant, dairy)).getDietaryTags()
        .contains(DietaryTag.GLUTEN_FREE));
  }

  @Test
  void shouldUnionAllergensExactlyWithoutMajorityRule() {
    Ingredient first = ingredient("FIRST", SpiceLevel.NONE,
        EnumSet.noneOf(DietaryTag.class),
        EnumSet.of(Allergen.GLUTEN, Allergen.MILK));
    Ingredient second = ingredient("SECOND", SpiceLevel.NONE,
        EnumSet.noneOf(DietaryTag.class),
        EnumSet.of(Allergen.MILK, Allergen.SESAME));

    assertEquals(EnumSet.of(Allergen.GLUTEN, Allergen.MILK,
        Allergen.SESAME), service.classify(taco(first, second)).getAllergens());
  }

  @Test
  void shouldUseHighestSpiceLevelAndKeepUnknownConservative() {
    Ingredient mild = ingredient("MILD", SpiceLevel.MILD,
        EnumSet.noneOf(DietaryTag.class), EnumSet.noneOf(Allergen.class));
    Ingredient hot = ingredient("HOT", SpiceLevel.HOT,
        EnumSet.noneOf(DietaryTag.class), EnumSet.noneOf(Allergen.class));
    Ingredient unknown = ingredient("UNKNOWN", SpiceLevel.UNKNOWN,
        EnumSet.noneOf(DietaryTag.class), EnumSet.noneOf(Allergen.class));

    assertEquals(SpiceLevel.HOT,
        service.classify(taco(mild, hot)).getSpiceLevel());
    assertEquals(SpiceLevel.UNKNOWN,
        service.classify(taco(hot, unknown)).getSpiceLevel());
  }

  @Test
  void shouldSubscribeToCanonicalIngredientsBeforeClassifying() {
    Taco taco = taco(new Ingredient("TRUSTED", null, null));
    taco.getIngredients().get(0).setDietaryTags(
        EnumSet.allOf(DietaryTag.class));
    Ingredient canonical = ingredient("TRUSTED", SpiceLevel.NONE,
        EnumSet.noneOf(DietaryTag.class), EnumSet.of(Allergen.MILK));
    PublisherProbe<Ingredient> lookup = PublisherProbe.of(
        reactor.core.publisher.Mono.just(canonical));
    when(repo.findById("TRUSTED")).thenReturn(lookup.mono());

    StepVerifier.create(service.resolveIngredients(taco)
            .map(service::classify))
        .assertNext(result -> {
          assertTrue(result.getDietaryTags().isEmpty());
          assertEquals(EnumSet.of(Allergen.MILK), result.getAllergens());
        })
        .verifyComplete();
    lookup.assertWasSubscribed();
    lookup.assertWasRequested();
  }

  private Taco taco(Ingredient... ingredients) {
    Taco taco = new Taco();
    taco.setId("TACO");
    taco.setName("Test taco");
    taco.setIngredients(Arrays.asList(ingredients));
    return taco;
  }

  private Ingredient ingredient(String id, SpiceLevel spice,
      java.util.Set<DietaryTag> tags, java.util.Set<Allergen> allergens) {
    Ingredient ingredient = new Ingredient(id, id, Type.SAUCE);
    ingredient.setDietaryTags(tags);
    ingredient.setAllergens(allergens);
    ingredient.setSpiceLevel(spice);
    return ingredient;
  }
}
