package tacos.design;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class IngredientCountRule implements TacoDesignRule {
  @Override
  public List<TacoDesignViolation> check(TacoDesignContext design) {
    int count = design.getIngredientIds().size();
    return count >= 2 && count <= 12 ? Collections.emptyList()
        : Collections.singletonList(new TacoDesignViolation(
            "TACO_INGREDIENT_COUNT", "A taco needs between 2 and 12 ingredients."));
  }
}
