package tacos.api.dto;

import java.math.BigDecimal;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Data;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;

@Data
@AllArgsConstructor
public class IngredientAdminResponse {
  private String id;
  private String name;
  private Type type;
  private BigDecimal unitPrice;
  private boolean available;
  private int stockOnHand;
  private int reorderLevel;
  private Long version;
  private Set<DietaryTag> dietaryTags;
  private Set<Allergen> allergens;
  private SpiceLevel spiceLevel;
}
