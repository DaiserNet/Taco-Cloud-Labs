package tacos.kitchen.delivery;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("kitchenOrderReceipt")
public class KitchenOrderReceipt {
  @Id private String orderId;
  private String eventId;
  private String status;
  private Instant receivedAt;

  protected KitchenOrderReceipt() { }

  public KitchenOrderReceipt(String orderId, String eventId, Instant receivedAt) {
    this.orderId = orderId;
    this.eventId = eventId;
    this.status = "RECEIVED";
    this.receivedAt = receivedAt;
  }

  public String getOrderId() { return orderId; }
  public String getEventId() { return eventId; }
  public String getStatus() { return status; }
  public Instant getReceivedAt() { return receivedAt; }
}
