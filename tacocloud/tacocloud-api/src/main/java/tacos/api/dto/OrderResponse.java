package tacos.api.dto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import lombok.Data;
import tacos.OrderStatus;

@Data
public class OrderResponse {
  private String id;
  private Date placedAt;
  private OrderStatus status;
  private Long version;
  private List<StatusChange> statusHistory;
  private String userId;
  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;
  private String paymentBrand;
  private String paymentLast4;
  private String currency;
  private BigDecimal subtotal;
  private boolean couponApplied;
  private BigDecimal discount;
  private BigDecimal total;
  private List<OrderItem> items;

  @Data
  public static class StatusChange {
    private OrderStatus from;
    private OrderStatus to;
    private String actorId;
    private String actorRole;
    private Date changedAt;
    private String origin;
    private String reason;
  }

  @Data
  public static class OrderItem {
    private TacoItem taco;
    private int quantity;
    private BigDecimal unitPriceAtPurchase;
    private BigDecimal subtotal;
  }

  @Data
  public static class TacoItem {
    private String id;
    private String name;
    private List<String> ingredientIds;
  }
}
