package tacos.design;

import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.SpiceLevel;

@Component
public class ExtremeSpiceNeedsTypeRule implements TacoDesignRule {
  private final boolean enabled;
  private final Ingredient.Type requiredType;

  public ExtremeSpiceNeedsTypeRule(
      @Value("${tacocloud.taco-physics.extreme-spice.enabled:true}") boolean enabled,
      @Value("${tacocloud.taco-physics.extreme-spice.required-type:VEGGIES}")
          Ingredient.Type requiredType) {
    this.enabled = enabled;
    this.requiredType = requiredType;
  }

  @Override
  public List<TacoDesignViolation> check(TacoDesignContext design) {
    boolean hasBalance = design.getResolvedIngredients().stream()
        .anyMatch(ingredient -> ingredient.getType() == requiredType
            && ingredient.getSpiceLevel() != SpiceLevel.EXTREME);
    return !enabled || !design.hasSpiceLevel(SpiceLevel.EXTREME)
        || hasBalance ? Collections.emptyList()
        : Collections.singletonList(new TacoDesignViolation(
            "TACO_EXTREME_NEEDS_BALANCE",
            "Extreme spice needs a non-extreme ingredient of type "
                + requiredType + "."));
  }
}
