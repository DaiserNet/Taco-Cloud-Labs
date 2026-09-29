package tacos.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class OrderEvent {
  public static final int CURRENT_VERSION = 1;

  private final String eventId;
  private final OrderEventType eventType;
  private final int version;
  private final String occurredAt;
  private final String correlationId;
  private final OrderEventPayload payload;

  @JsonCreator
  public OrderEvent(@JsonProperty("eventId") String eventId,
      @JsonProperty("eventType") OrderEventType eventType,
      @JsonProperty("version") int version,
      @JsonProperty("occurredAt") String occurredAt,
      @JsonProperty("correlationId") String correlationId,
      @JsonProperty("payload") OrderEventPayload payload) {
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    UUID.fromString(eventId);
    this.eventType = Objects.requireNonNull(eventType, "eventType");
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    this.version = version;
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    Instant.parse(occurredAt);
    this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    if (correlationId.isEmpty()) {
      throw new IllegalArgumentException("correlationId must not be empty");
    }
    this.payload = Objects.requireNonNull(payload, "payload");
  }

  public static OrderEvent created(String correlationId, OrderEventPayload payload) {
    return new OrderEvent(UUID.randomUUID().toString(), OrderEventType.ORDER_CREATED,
        CURRENT_VERSION, Instant.now().toString(), correlationId, payload);
  }

  public String getEventId() { return eventId; }
  public OrderEventType getEventType() { return eventType; }
  public int getVersion() { return version; }
  public String getOccurredAt() { return occurredAt; }
  public String getCorrelationId() { return correlationId; }
  public OrderEventPayload getPayload() { return payload; }
}
