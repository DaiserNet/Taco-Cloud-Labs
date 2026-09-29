package tacos;

import java.util.Date;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OrderStatusChange {
  private OrderStatus from;
  private OrderStatus to;
  private String actorId;
  private String actorRole;
  private Date changedAt;
  private String origin;
  private String reason;

  public OrderStatusChange(OrderStatus from, OrderStatus to, String actorId,
      String actorRole, Date changedAt, String origin, String reason) {
    this.from = from;
    this.to = to;
    this.actorId = actorId;
    this.actorRole = actorRole;
    this.changedAt = changedAt;
    this.origin = origin;
    this.reason = reason;
  }
}
