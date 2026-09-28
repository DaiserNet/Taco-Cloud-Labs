package tacos.api.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;
import tacos.api.dto.IngredientAdminResponse;
import tacos.api.dto.IngredientRequest;
import tacos.api.dto.IngredientResponse;

@Component
public class IngredientMapper {

  public Ingredient toEntity(IngredientRequest request) {
    Ingredient ingredient = new Ingredient(
        request.getId(), request.getName(), request.getType());
    updateEntity(request, ingredient);
    return ingredient;
  }

  public void updateEntity(IngredientRequest request, Ingredient ingredient) {
    ingredient.setName(request.getName());
    ingredient.setType(request.getType());
    if (request.getDietaryTags() != null) {
      ingredient.setDietaryTags(dietaryTags(request.getDietaryTags()));
    }
    if (request.getAllergens() != null) {
      ingredient.setAllergens(allergens(request.getAllergens()));
    }
    if (request.getSpiceLevel() != null) {
      ingredient.setSpiceLevel(request.getSpiceLevel());
    }
  }

  public IngredientResponse toResponse(Ingredient ingredient) {
    return new IngredientResponse(
        ingredient.getId(), ingredient.getName(), ingredient.getType(),
        displayPrice(ingredient.getUnitPrice()),
        ingredient.isAvailable() && ingredient.getStockOnHand() > 0,
        dietaryTags(ingredient.getDietaryTags()),
        allergens(ingredient.getAllergens()), spiceLevel(ingredient));
  }

  public IngredientAdminResponse toAdminResponse(Ingredient ingredient) {
    return new IngredientAdminResponse(
        ingredient.getId(), ingredient.getName(), ingredient.getType(),
        displayPrice(ingredient.getUnitPrice()), ingredient.isAvailable(),
        ingredient.getStockOnHand(), ingredient.getReorderLevel(),
        ingredient.getVersion(), dietaryTags(ingredient.getDietaryTags()),
        allergens(ingredient.getAllergens()), spiceLevel(ingredient));
  }

  private Set<DietaryTag> dietaryTags(Set<DietaryTag> tags) {
    return tags == null || tags.isEmpty()
        ? EnumSet.noneOf(DietaryTag.class) : EnumSet.copyOf(tags);
  }

  private Set<Allergen> allergens(Set<Allergen> values) {
    return values == null || values.isEmpty()
        ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(values);
  }

  private SpiceLevel spiceLevel(Ingredient ingredient) {
    return ingredient.getSpiceLevel() == null
        ? SpiceLevel.UNKNOWN : ingredient.getSpiceLevel();
  }

  private BigDecimal displayPrice(BigDecimal price) {
    return (price == null ? BigDecimal.ZERO : price)
        .setScale(2, RoundingMode.HALF_UP);
  }
}
