package tacos.api.dto;

import java.util.Date;
import java.util.List;

import lombok.Data;
import tacos.OrderStatus;

@Data
public class OrderResponse {
  private String id;
  private Date placedAt;
  private OrderStatus status;
  private String userId;
  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;
  private String paymentLast4;
  private List<TacoItem> tacos;

  @Data
  public static class TacoItem {
    private String id;
    private String name;
    private List<String> ingredientIds;
  }
}
