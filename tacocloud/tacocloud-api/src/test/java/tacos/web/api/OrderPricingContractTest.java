package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.OrderMapper;

class OrderPricingContractTest {

  @Test
  void shouldUseQuantityItemsWithoutClientOwnedMoneyFields() {
    Set<String> requestFields = fields(OrderCreateRequest.class);
    Set<String> orderFields = fields(TacoOrder.class);

    assertTrue(requestFields.contains("items"));
    assertFalse(requestFields.contains("tacos"));
    assertFalse(requestFields.contains("currency"));
    assertFalse(requestFields.contains("subtotal"));
    assertFalse(requestFields.contains("total"));
    assertTrue(orderFields.containsAll(Arrays.asList(
        "items", "currency", "subtotal", "total")));
  }

  @Test
  void shouldExposeCalculatedMoneyAndPriceSnapshotsInResponse() {
    OrderResponse response = new OrderMapper().toResponse(new TacoOrder());
    JsonNode json = new ObjectMapper().valueToTree(response);

    assertTrue(json.has("items"));
    assertTrue(json.has("currency"));
    assertTrue(json.has("subtotal"));
    assertTrue(json.has("total"));
    assertFalse(json.has("tacos"));
  }

  private Set<String> fields(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .map(Field::getName)
        .collect(Collectors.toSet());
  }
}
