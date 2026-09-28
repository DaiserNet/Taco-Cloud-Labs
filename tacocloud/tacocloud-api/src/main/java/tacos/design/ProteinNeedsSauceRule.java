package tacos.design;

import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tacos.Ingredient;

@Component
public class ProteinNeedsSauceRule implements TacoDesignRule {
  private final boolean enabled;
  private final Ingredient.Type requiredType;

  public ProteinNeedsSauceRule(
      @Value("${tacocloud.taco-physics.protein-sauce.enabled:true}") boolean enabled,
      @Value("${tacocloud.taco-physics.protein-sauce.required-type:SAUCE}")
          Ingredient.Type requiredType) {
    this.enabled = enabled;
    this.requiredType = requiredType;
  }

  @Override
  public List<TacoDesignViolation> check(TacoDesignContext design) {
    return !enabled || !design.hasType(Ingredient.Type.PROTEIN)
        || design.hasType(requiredType) ? Collections.emptyList()
        : Collections.singletonList(new TacoDesignViolation(
            "TACO_PROTEIN_NEEDS_SAUCE",
            "Protein needs an ingredient of type " + requiredType + "."));
  }
}
