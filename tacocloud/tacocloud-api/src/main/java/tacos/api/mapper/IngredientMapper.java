package tacos.api.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.api.dto.IngredientAdminResponse;
import tacos.api.dto.IngredientRequest;
import tacos.api.dto.IngredientResponse;

@Component
public class IngredientMapper {

  public Ingredient toEntity(IngredientRequest request) {
    return new Ingredient(request.getId(), request.getName(), request.getType());
  }

  public void updateEntity(IngredientRequest request, Ingredient ingredient) {
    ingredient.setName(request.getName());
    ingredient.setType(request.getType());
  }

  public IngredientResponse toResponse(Ingredient ingredient) {
    return new IngredientResponse(
        ingredient.getId(), ingredient.getName(), ingredient.getType(),
        displayPrice(ingredient.getUnitPrice()),
        ingredient.isAvailable() && ingredient.getStockOnHand() > 0);
  }

  public IngredientAdminResponse toAdminResponse(Ingredient ingredient) {
    return new IngredientAdminResponse(
        ingredient.getId(), ingredient.getName(), ingredient.getType(),
        displayPrice(ingredient.getUnitPrice()), ingredient.isAvailable(),
        ingredient.getStockOnHand(), ingredient.getReorderLevel(),
        ingredient.getVersion());
  }

  private BigDecimal displayPrice(BigDecimal price) {
    return (price == null ? BigDecimal.ZERO : price)
        .setScale(2, RoundingMode.HALF_UP);
  }
}
