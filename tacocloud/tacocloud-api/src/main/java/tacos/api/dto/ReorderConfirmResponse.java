package tacos.api.dto;

import lombok.Value;

@Value
public class ReorderConfirmResponse {
  OrderResponse order;
  boolean replayed;
}
