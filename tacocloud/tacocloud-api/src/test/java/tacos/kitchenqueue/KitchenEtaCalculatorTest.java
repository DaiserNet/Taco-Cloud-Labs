package tacos.kitchenqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import tacos.Ingredient;
import tacos.OrderLine;
import tacos.Taco;
import tacos.TacoOrder;

class KitchenEtaCalculatorTest {
  @Test
  void shouldIncreaseWithQueueQuantityAndComplexityDeterministically() {
    KitchenEtaCalculator eta = new KitchenEtaCalculator(4, 3, 2, 1);
    TacoOrder simple = order(1, 2);
    TacoOrder complex = order(2, 4);

    assertEquals(6, eta.estimate(0, simple));
    assertEquals(12, eta.estimate(0, complex));
    assertEquals(18, eta.estimate(2, complex));
    assertEquals(18, eta.estimate(2, complex));
  }

  @Test
  void shouldRejectNegativeConfiguration() {
    assertThrows(IllegalArgumentException.class,
        () -> new KitchenEtaCalculator(4, -1, 2, 1));
  }

  private TacoOrder order(int quantity, int ingredientCount) {
    Taco taco = new Taco();
    Ingredient[] ingredients = new Ingredient[ingredientCount];
    for (int i = 0; i < ingredientCount; i++) {
      ingredients[i] = new Ingredient("I" + i, "Ingredient " + i,
          Ingredient.Type.PROTEIN);
    }
    taco.setIngredients(Arrays.asList(ingredients));
    OrderLine line = new OrderLine();
    line.setTaco(taco);
    line.setQuantity(quantity);
    TacoOrder order = new TacoOrder();
    order.setItems(Arrays.asList(line));
    return order;
  }
}
