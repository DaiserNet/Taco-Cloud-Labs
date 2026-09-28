package tacos.api.dto;

import java.util.Date;
import java.util.List;

import lombok.Value;

@Value
public class TacoResponse {
  String id;
  String name;
  Date createdAt;
  List<IngredientResponse> ingredients;
  TacoClassification classification;
}
