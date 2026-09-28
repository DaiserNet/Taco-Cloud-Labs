package tacos.api.dto;

import lombok.Data;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;

@Data
public class TacoSearchRequest {
  private String name;
  private String ingredientId;
  private DietaryTag diet;
  private Allergen excludeAllergen;
  private SpiceLevel spice;
  private int page = 0;
  private int size = 20;
  private String sort = "createdAt,desc";
}
