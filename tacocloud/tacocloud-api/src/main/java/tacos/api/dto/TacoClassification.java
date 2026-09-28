package tacos.api.dto;

import java.util.Set;

import lombok.Value;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;

@Value
public class TacoClassification {
  String tacoId;
  Set<DietaryTag> dietaryTags;
  Set<Allergen> allergens;
  SpiceLevel spiceLevel;
  String disclaimer;
}
