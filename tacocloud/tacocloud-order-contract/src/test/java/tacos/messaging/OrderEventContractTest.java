package tacos.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OrderEventContractTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void shouldRoundTripVersionOneAndMatchSnapshot() throws Exception {
    OrderEvent event = sample();
    JsonNode actual = mapper.readTree(mapper.writeValueAsString(event));
    try (InputStream fixture = getClass().getResourceAsStream("/order-event-v1.json")) {
      assertEquals(mapper.readTree(fixture), actual);
    }
    OrderEvent restored = mapper.treeToValue(actual, OrderEvent.class);
    assertEquals("FLTO", restored.getPayload().getItems().get(0)
        .getIngredients().get(0).getId());
    assertEquals(OrderEventType.ORDER_CREATED, restored.getEventType());
  }

  @Test
  void shouldIgnoreCompatibleAdditionalFieldsAtEveryLevel() throws Exception {
    JsonNode original = mapper.readTree(mapper.writeValueAsString(sample()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) original).put("future", "value");
    ((com.fasterxml.jackson.databind.node.ObjectNode) original.get("payload"))
        .put("future", "value");
    ((com.fasterxml.jackson.databind.node.ObjectNode) original.get("payload")
        .get("items").get(0)).put("future", "value");
    ((com.fasterxml.jackson.databind.node.ObjectNode) original.get("payload")
        .get("items").get(0).get("ingredients").get(0)).put("future", "value");
    OrderEvent restored = mapper.treeToValue(original, OrderEvent.class);
    assertEquals("ORDER-1", restored.getPayload().getOrderId());
  }

  @Test
  void shouldGenerateUniqueValidIdentifiersAndRejectMissingEnvelopeFields() {
    OrderEvent first = OrderEvent.created("ORDER-1", sample().getPayload());
    OrderEvent second = OrderEvent.created("ORDER-1", sample().getPayload());
    UUID.fromString(first.getEventId());
    Instant.parse(first.getOccurredAt());
    assertNotEquals(first.getEventId(), second.getEventId());
    assertEquals("ORDER-1", first.getCorrelationId());
    assertEquals(1, first.getVersion());
    assertThrows(NullPointerException.class, () -> new OrderEvent(null,
        OrderEventType.ORDER_CREATED, 1, first.getOccurredAt(), "ORDER-1",
        first.getPayload()));
  }

  @Test
  void shouldNeverExposeSensitiveFieldsInContractJson() throws Exception {
    String json = mapper.writeValueAsString(sample());
    for (String forbidden : new String[] {"pan", "cvv", "password", "user",
        "paymentMethod", "deliveryStreet"}) {
      assertFalse(json.toLowerCase().contains(forbidden.toLowerCase()));
    }
    assertTrue(json.contains("Veggie taco"));
  }

  private OrderEvent sample() {
    OrderEventPayload.Ingredient ingredient = new OrderEventPayload.Ingredient(
        "FLTO", "Flour Tortilla", "WRAP");
    OrderEventPayload.Item item = new OrderEventPayload.Item("Veggie taco", 2,
        Collections.singletonList(ingredient));
    return new OrderEvent("123e4567-e89b-42d3-a456-426614174000",
        OrderEventType.ORDER_CREATED, 1, "2026-01-02T03:04:05Z", "ORDER-1",
        new OrderEventPayload("ORDER-1", Collections.singletonList(item)));
  }
}
