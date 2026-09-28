package tacos.design;

import java.util.Arrays;

import tacos.Ingredient;
import tacos.data.IngredientRepository;

public final class TacoDesignTestSupport {
  private TacoDesignTestSupport() {
  }

  public static TacoDesignValidator validator(IngredientRepository repo) {
    return new TacoDesignValidator(repo, Arrays.asList(
        new IngredientCountRule(), new ExactlyOneBaseRule(),
        new NoDuplicateIngredientsRule(), new KnownIngredientsRule(),
        new AvailableIngredientsRule(),
        new ExtremeSpiceNeedsTypeRule(true, Ingredient.Type.VEGGIES),
        new ProteinNeedsSauceRule(true, Ingredient.Type.SAUCE)));
  }
}
