package tacos.api.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import tacos.Ingredient.Type;

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
}
