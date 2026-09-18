package tacos.api.mapper;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
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
        ingredient.getId(), ingredient.getName(), ingredient.getType());
  }
}
