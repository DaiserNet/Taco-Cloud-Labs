package tacos.design;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class KnownIngredientsRule implements TacoDesignRule {
  @Override
  public List<TacoDesignViolation> check(TacoDesignContext design) {
    List<TacoDesignViolation> violations = new ArrayList<>();
    Set<String> checked = new LinkedHashSet<>(design.getIngredientIds());
    for (String id : checked) {
      if (!StringUtils.hasText(id)) {
        violations.add(new TacoDesignViolation(
            "TACO_INGREDIENT_ID_REQUIRED", "Every ingredient needs an ID."));
      } else if (design.find(id) == null) {
        violations.add(new TacoDesignViolation(
            "TACO_INGREDIENT_UNKNOWN", "Unknown ingredient: " + id));
      }
    }
    return violations;
  }
}
