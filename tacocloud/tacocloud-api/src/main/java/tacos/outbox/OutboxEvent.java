package tacos.outbox;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("orderOutbox")
@CompoundIndex(name = "outbox_claim", def = "{'status': 1, 'nextAttemptAt': 1, 'leaseUntil': 1, 'createdAt': 1}")
public class OutboxEvent {
  public enum Status { NEW, PUBLISHING, PUBLISHED, FAILED }

  @Id private String eventId;
  private String payloadJson;
  private int version;
  private Status status;
  private int attempts;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant nextAttemptAt;
  private Instant leaseUntil;
  private String claimId;
  private Instant publishedAt;
  private String lastError;

  protected OutboxEvent() { }

  public OutboxEvent(String eventId, String payloadJson, int version, Instant now) {
    this.eventId = eventId;
    this.payloadJson = payloadJson;
    this.version = version;
    this.status = Status.NEW;
    this.createdAt = now;
    this.updatedAt = now;
    this.nextAttemptAt = now;
  }

  public String getEventId() { return eventId; }
  public String getPayloadJson() { return payloadJson; }
  public int getVersion() { return version; }
  public Status getStatus() { return status; }
  public int getAttempts() { return attempts; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getNextAttemptAt() { return nextAttemptAt; }
  public Instant getLeaseUntil() { return leaseUntil; }
  public String getClaimId() { return claimId; }
  public Instant getPublishedAt() { return publishedAt; }
  public String getLastError() { return lastError; }
}
