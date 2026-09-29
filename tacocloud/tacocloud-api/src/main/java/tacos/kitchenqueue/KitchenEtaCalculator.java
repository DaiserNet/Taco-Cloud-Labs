package tacos.kitchenqueue;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tacos.OrderLine;
import tacos.TacoOrder;

@Component
public class KitchenEtaCalculator {
  private final int baseMinutes;
  private final int perQueuedOrderMinutes;
  private final int perUnitMinutes;
  private final int perExtraIngredientMinutes;

  public KitchenEtaCalculator(
      @Value("${tacocloud.kitchen.eta.base-minutes:4}") int baseMinutes,
      @Value("${tacocloud.kitchen.eta.per-queued-order-minutes:3}")
          int perQueuedOrderMinutes,
      @Value("${tacocloud.kitchen.eta.per-unit-minutes:2}") int perUnitMinutes,
      @Value("${tacocloud.kitchen.eta.per-extra-ingredient-minutes:1}")
          int perExtraIngredientMinutes) {
    if (baseMinutes < 0 || perQueuedOrderMinutes < 0
        || perUnitMinutes < 0 || perExtraIngredientMinutes < 0) {
      throw new IllegalArgumentException("Kitchen ETA factors must not be negative.");
    }
    this.baseMinutes = baseMinutes;
    this.perQueuedOrderMinutes = perQueuedOrderMinutes;
    this.perUnitMinutes = perUnitMinutes;
    this.perExtraIngredientMinutes = perExtraIngredientMinutes;
  }

  public int estimate(long queuedAhead, TacoOrder order) {
    long units = 0;
    long complexity = 0;
    List<OrderLine> lines = order.getItems();
    if (lines != null) {
      for (OrderLine line : lines) {
        if (line == null || line.getTaco() == null) {
          continue;
        }
        int quantity = Math.max(0, line.getQuantity());
        int ingredientCount = line.getTaco().getIngredients() == null
            ? 0 : line.getTaco().getIngredients().size();
        units += quantity;
        complexity += (long) quantity * Math.max(0, ingredientCount - 2);
      }
    }
    return Math.toIntExact(baseMinutes
        + Math.max(0, queuedAhead) * perQueuedOrderMinutes
        + units * perUnitMinutes
        + complexity * perExtraIngredientMinutes);
  }
}
