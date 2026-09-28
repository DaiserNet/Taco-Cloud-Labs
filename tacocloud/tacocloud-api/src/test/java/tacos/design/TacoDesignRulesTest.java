package tacos.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tacos.Ingredient;
import tacos.SpiceLevel;

class TacoDesignRulesTest {

  @ParameterizedTest
  @CsvSource({"1, TACO_INGREDIENT_COUNT", "2, VALID",
      "12, VALID", "13, TACO_INGREDIENT_COUNT"})
  void shouldRequireTwoToTwelveIngredients(int count, String expected) {
    List<String> ids = java.util.stream.IntStream.range(0, count)
        .mapToObj(index -> "I" + index).collect(Collectors.toList());
    assertCode(new IngredientCountRule(),
        context(ids.toArray(new String[0])), expected);
  }

  @Test
  void shouldRequireExactlyOneWrapOrBowl() {
    assertCode(new ExactlyOneBaseRule(), context("PROTEIN", "SAUCE"),
        "TACO_BASE_COUNT");
    assertCode(new ExactlyOneBaseRule(), context("WRAP", "BOWL"),
        "TACO_BASE_COUNT");
    assertCode(new ExactlyOneBaseRule(), context("BOWL", "SAUCE"), "VALID");
  }

  @Test
  void shouldRejectDuplicateIngredientIds() {
    assertCode(new NoDuplicateIngredientsRule(), context("WRAP", "WRAP"),
        "TACO_DUPLICATE_INGREDIENT");
    assertCode(new NoDuplicateIngredientsRule(), context("WRAP", "SAUCE"),
        "VALID");
  }

  @Test
  void shouldIdentifyUnknownAndUnavailableIngredients() {
    assertCode(new KnownIngredientsRule(), context("WRAP", "MISSING"),
        "TACO_INGREDIENT_UNKNOWN");
    Ingredient wrap = ingredient("WRAP", Ingredient.Type.WRAP);
    wrap.setAvailable(false);
    assertCode(new AvailableIngredientsRule(), context(
        Collections.singletonList("WRAP"), Collections.singletonMap("WRAP", wrap)),
        "TACO_INGREDIENT_UNAVAILABLE");
  }

  @Test
  void shouldApplyConfigurableExtremeSpiceRule() {
    TacoDesignContext design = context("WRAP", "EXTREME");
    assertCode(new ExtremeSpiceNeedsTypeRule(true, Ingredient.Type.VEGGIES),
        design, "TACO_EXTREME_NEEDS_BALANCE");
    assertCode(new ExtremeSpiceNeedsTypeRule(false, Ingredient.Type.VEGGIES),
        design, "VALID");
    assertCode(new ExtremeSpiceNeedsTypeRule(true, Ingredient.Type.SAUCE),
        context("WRAP", "EXTREME", "SAUCE"), "VALID");
    assertCode(new ExtremeSpiceNeedsTypeRule(true, Ingredient.Type.VEGGIES),
        context("WRAP", "EXTREME_VEG"), "TACO_EXTREME_NEEDS_BALANCE");
    assertCode(new ExtremeSpiceNeedsTypeRule(true, Ingredient.Type.VEGGIES),
        context("WRAP", "EXTREME_VEG", "VEGGIES"), "VALID");
  }

  @Test
  void shouldApplyConfigurableProteinSauceRule() {
    TacoDesignContext design = context("WRAP", "PROTEIN");
    assertCode(new ProteinNeedsSauceRule(true, Ingredient.Type.SAUCE),
        design, "TACO_PROTEIN_NEEDS_SAUCE");
    assertCode(new ProteinNeedsSauceRule(false, Ingredient.Type.SAUCE),
        design, "VALID");
    assertCode(new ProteinNeedsSauceRule(true, Ingredient.Type.VEGGIES),
        context("WRAP", "PROTEIN", "VEGGIES"), "VALID");
  }

  private TacoDesignContext context(String... ids) {
    List<String> requested = Arrays.asList(ids);
    Map<String, Ingredient> catalog = new HashMap<>();
    for (String id : requested) {
      if (!"MISSING".equals(id)) {
        Ingredient.Type type = "WRAP".equals(id) ? Ingredient.Type.WRAP
            : "BOWL".equals(id) ? Ingredient.Type.BOWL
            : "PROTEIN".equals(id) ? Ingredient.Type.PROTEIN
            : "SAUCE".equals(id) || "EXTREME".equals(id)
                ? Ingredient.Type.SAUCE
            : Ingredient.Type.VEGGIES;
        Ingredient ingredient = ingredient(id, type);
        if (id.startsWith("EXTREME")) {
          ingredient.setSpiceLevel(SpiceLevel.EXTREME);
        }
        catalog.put(id, ingredient);
      }
    }
    return context(requested, catalog);
  }

  private TacoDesignContext context(List<String> ids,
      Map<String, Ingredient> catalog) {
    return new TacoDesignContext(ids, catalog);
  }

  private Ingredient ingredient(String id, Ingredient.Type type) {
    Ingredient ingredient = new Ingredient(id, id, type);
    ingredient.setAvailable(true);
    ingredient.setStockOnHand(10);
    return ingredient;
  }

  private void assertCode(TacoDesignRule rule,
      TacoDesignContext context, String expected) {
    List<TacoDesignViolation> violations = rule.check(context);
    if ("VALID".equals(expected)) {
      assertTrue(violations.isEmpty());
    } else {
      assertEquals(expected, violations.get(0).getCode());
    }
  }
}
