package tacos.web.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tacos.OrderLine;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;

/** Maps the persisted order to the small, self-contained kitchen contract. */
final class OrderEventFactory {
  private OrderEventFactory() { }

  static OrderEvent created(TacoOrder order, String correlationId) {
    String orderId = Objects.requireNonNull(order.getId(), "saved order ID");
    List<OrderEventPayload.Item> items = new ArrayList<>();
    if (order.getItems() != null && !order.getItems().isEmpty()) {
      for (OrderLine line : order.getItems()) {
        items.add(item(line.getTaco(), line.getQuantity()));
      }
    } else if (order.getTacos() != null) {
      for (Taco taco : order.getTacos()) {
        items.add(item(taco, 1));
      }
    }
    return OrderEvent.created(correlationId,
        new OrderEventPayload(orderId, items));
  }

  private static OrderEventPayload.Item item(Taco taco, int quantity) {
    List<OrderEventPayload.Ingredient> ingredients = new ArrayList<>();
    if (taco != null && taco.getIngredients() != null) {
      taco.getIngredients().forEach(ingredient -> ingredients.add(
          new OrderEventPayload.Ingredient(ingredient.getId(),
              ingredient.getName(), ingredient.getType().name())));
    }
    return new OrderEventPayload.Item(
        taco == null || taco.getName() == null ? "Unnamed taco" : taco.getName(),
        quantity, ingredients);
  }
}
