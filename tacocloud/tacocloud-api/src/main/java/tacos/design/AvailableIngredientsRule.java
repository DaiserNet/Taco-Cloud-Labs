package tacos.design;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Ingredient;

@Component
public class AvailableIngredientsRule implements TacoDesignRule {
  @Override
  public List<TacoDesignViolation> check(TacoDesignContext design) {
    List<TacoDesignViolation> violations = new ArrayList<>();
    for (Ingredient ingredient : design.getResolvedIngredients()) {
      if (!ingredient.isAvailable() || ingredient.getStockOnHand() < 1) {
        violations.add(new TacoDesignViolation(
            "TACO_INGREDIENT_UNAVAILABLE",
            "Ingredient is unavailable: " + ingredient.getId()));
      }
    }
    return violations;
  }
}
