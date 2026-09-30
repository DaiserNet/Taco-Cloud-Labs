package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.Ingredient;
import tacos.OrderLine;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.messaging.OrderEvent;

class OrderEventFactoryTest {
  @Test
  void shouldPublishSelfContainedKitchenSnapshotWithoutSensitiveOrderData()
      throws Exception {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-1");
    order.setDeliveryStreet("SECRET-STREET");
    order.setPaymentMethodId("SECRET-PAYMENT");
    order.setPaymentLast4("9999");
    User user = new User("test", "SECRET-PASSWORD", "Test User", "Street",
        "City", "State", "Zip", "123", "test@example.com");
    order.setUser(user);
    Taco taco = new Taco();
    taco.setName("Veggie taco");
    taco.setIngredients(Arrays.asList(new Ingredient("FLTO", "Flour Tortilla",
        Ingredient.Type.WRAP)));
    OrderLine line = new OrderLine();
    line.setTaco(taco);
    line.setQuantity(2);
    order.addItem(line);

    OrderEvent event = OrderEventFactory.created(order, "request-123");
    String json = new ObjectMapper().writeValueAsString(event);
    assertEquals("request-123", event.getCorrelationId());
    assertFalse(event.getCorrelationId().equals(order.getId()));
    assertEquals("FLTO", event.getPayload().getItems().get(0)
        .getIngredients().get(0).getId());
    assertEquals(2, event.getPayload().getItems().get(0).getQuantity());
    assertTrue(json.contains("Flour Tortilla"));
    assertFalse(json.contains("SECRET-"));
    assertFalse(json.contains("9999"));
    assertFalse(json.contains("user"));
  }
}
