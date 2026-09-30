package tacos.idempotency;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import tacos.api.dto.OrderResponse;

@Document(collection = "orderIdempotency")
@CompoundIndex(name = "idempotency_user_key_unique", unique = true,
    def = "{'userId': 1, 'key': 1}")
public class IdempotencyRecord {
  public enum Status { IN_PROGRESS, COMPLETED }

  @Id private String id;
  private String userId;
  private String key;
  private String requestHash;
  private String orderId;
  private String reservationId;
  private Status status;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant completedAt;
  @Indexed(name = "idempotency_completed_expiry", expireAfterSeconds = 0)
  private Instant expiresAt;
  private OrderResponse response;

  protected IdempotencyRecord() { }

  public IdempotencyRecord(String id, String userId, String key,
      String requestHash, String orderId, String reservationId, Instant now) {
    this.id = id;
    this.userId = userId;
    this.key = key;
    this.requestHash = requestHash;
    this.orderId = orderId;
    this.reservationId = reservationId;
    this.status = Status.IN_PROGRESS;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void complete(OrderResponse result, Instant now, int retentionHours) {
    this.response = result;
    this.status = Status.COMPLETED;
    this.updatedAt = now;
    this.completedAt = now;
    this.expiresAt = now.plus(retentionHours, ChronoUnit.HOURS);
  }

  public String getId() { return id; }
  public String getUserId() { return userId; }
  public String getKey() { return key; }
  public String getRequestHash() { return requestHash; }
  public String getOrderId() { return orderId; }
  public String getReservationId() { return reservationId; }
  public Status getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public Instant getExpiresAt() { return expiresAt; }
  public OrderResponse getResponse() { return response; }
}
