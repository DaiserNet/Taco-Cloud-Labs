package tacos.api.dto;

import lombok.Value;

@Value
public class FavoriteResponse {
  String tacoId;
  String tacoName;
  boolean orphaned;
}
