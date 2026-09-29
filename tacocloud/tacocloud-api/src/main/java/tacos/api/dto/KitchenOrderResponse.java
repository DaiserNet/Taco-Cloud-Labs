package tacos.api.dto;

import java.util.Date;
import java.util.List;

import lombok.Data;
import tacos.OrderStatus;

@Data
public class KitchenOrderResponse {
  private String id;
  private Date placedAt;
  private OrderStatus status;
  private Long version;
  private String stationId;
  private String cookId;
  private int estimatedPrepMinutes;
  private List<Item> items;

  @Data
  public static class Item {
    private String tacoName;
    private int quantity;
    private List<String> ingredientIds;
    private List<String> ingredientNames;
  }
}
