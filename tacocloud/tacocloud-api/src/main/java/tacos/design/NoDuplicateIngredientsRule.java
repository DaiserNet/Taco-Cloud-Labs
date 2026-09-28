package tacos.design;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

@Component
public class NoDuplicateIngredientsRule implements TacoDesignRule {
  @Override
  public List<TacoDesignViolation> check(TacoDesignContext design) {
    Set<String> seen = new HashSet<>();
    Set<String> repeated = new TreeSet<>();
    for (String id : design.getIngredientIds()) {
      if (id != null && !seen.add(id)) {
        repeated.add(id);
      }
    }
    List<TacoDesignViolation> violations = new ArrayList<>();
    repeated.forEach(id -> violations.add(new TacoDesignViolation(
        "TACO_DUPLICATE_INGREDIENT", "Ingredient is repeated: " + id)));
    return violations;
  }
}
