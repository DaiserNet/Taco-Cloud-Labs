package tacos.design;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import tacos.Ingredient;
import tacos.SpiceLevel;

public class TacoDesignContext {
  private final List<String> ingredientIds;
  private final Map<String, Ingredient> catalog;

  public TacoDesignContext(List<String> ingredientIds,
      Map<String, Ingredient> catalog) {
    this.ingredientIds = Collections.unmodifiableList(
        new ArrayList<>(ingredientIds));
    this.catalog = catalog;
  }

  public List<String> getIngredientIds() {
    return ingredientIds;
  }

  public Ingredient find(String id) {
    return catalog.get(id);
  }

  public List<Ingredient> getResolvedIngredients() {
    return ingredientIds.stream().map(catalog::get)
        .filter(Objects::nonNull).collect(Collectors.toList());
  }

  public boolean hasType(Ingredient.Type type) {
    return getResolvedIngredients().stream()
        .anyMatch(ingredient -> ingredient.getType() == type);
  }

  public boolean hasSpiceLevel(SpiceLevel level) {
    return getResolvedIngredients().stream()
        .anyMatch(ingredient -> ingredient.getSpiceLevel() == level);
  }
}
