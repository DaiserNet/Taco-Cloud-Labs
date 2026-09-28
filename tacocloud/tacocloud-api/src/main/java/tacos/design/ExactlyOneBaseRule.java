package tacos.design;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Ingredient;

@Component
public class ExactlyOneBaseRule implements TacoDesignRule {
  @Override
  public List<TacoDesignViolation> check(TacoDesignContext design) {
    long bases = design.getResolvedIngredients().stream()
        .filter(ingredient -> ingredient.getType() == Ingredient.Type.WRAP
            || ingredient.getType() == Ingredient.Type.BOWL)
        .count();
    return bases == 1 ? Collections.emptyList()
        : Collections.singletonList(new TacoDesignViolation(
            "TACO_BASE_COUNT", "A taco needs exactly one wrap or bowl."));
  }
}
