package tacos.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.OrderLine;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.IngredientRepository;

class OrderPricingServiceTest {

  private IngredientRepository ingredientRepo;
  private OrderPricingService service;

  @BeforeEach
  void setUp() {
    ingredientRepo = mock(IngredientRepository.class);
    service = new OrderPricingService(ingredientRepo, 10, "USD");
  }

  @Test
  void shouldCalculateDecimalPricesAndMultiplyByQuantity() {
    Ingredient wrap = ingredient("WRAP", "1.10");
    Ingredient salsa = ingredient("SLSA", "0.35");
    when(ingredientRepo.findById("WRAP")).thenReturn(Mono.just(wrap));
    when(ingredientRepo.findById("SLSA")).thenReturn(Mono.just(salsa));
    TacoOrder order = order(2, "WRAP", "SLSA");

    StepVerifier.create(service.price(order))
        .assertNext(priced -> {
          OrderLine line = priced.getItems().get(0);
          assertEquals(new BigDecimal("1.45"), line.getUnitPriceAtPurchase());
          assertEquals(new BigDecimal("2.90"), line.getSubtotal());
          assertEquals(new BigDecimal("2.90"), priced.getSubtotal());
          assertEquals(new BigDecimal("2.90"), priced.getTotal());
          assertEquals("USD", priced.getCurrency());
          assertEquals(Arrays.asList(wrap, salsa), line.getTaco().getIngredients());
        })
        .verifyComplete();
  }

  @Test
  void shouldKeepPriceSnapshotWhenCatalogPriceChanges() {
    Ingredient wrap = ingredient("WRAP", "1.10");
    when(ingredientRepo.findById("WRAP")).thenReturn(Mono.just(wrap));
    TacoOrder order = order(3, "WRAP");

    StepVerifier.create(service.price(order))
        .assertNext(priced -> {
          wrap.setUnitPrice(new BigDecimal("9.99"));
          assertEquals(new BigDecimal("1.10"),
              priced.getItems().get(0).getUnitPriceAtPurchase());
          assertEquals(new BigDecimal("3.30"), priced.getTotal());
        })
        .verifyComplete();
  }

  @Test
  void shouldRejectQuantityAboveConfiguredMaximumBeforeCatalogAccess() {
    TacoOrder order = order(11, "WRAP");

    StepVerifier.create(service.price(order))
        .expectErrorMatches(error -> error instanceof OrderPricingException
            && "ORDER_QUANTITY_INVALID".equals(
                ((OrderPricingException) error).getCode()))
        .verify();

    verifyNoInteractions(ingredientRepo);
  }

  @Test
  void shouldIdentifyUnknownIngredient() {
    when(ingredientRepo.findById("UNKNOWN")).thenReturn(Mono.empty());

    StepVerifier.create(service.price(order(1, "UNKNOWN")))
        .expectErrorMatches(error -> error instanceof OrderPricingException
            && "ORDER_INGREDIENT_NOT_FOUND".equals(
                ((OrderPricingException) error).getCode())
            && error.getMessage().contains("UNKNOWN"))
        .verify();
  }

  @Test
  void shouldRejectUnavailableIngredient() {
    Ingredient unavailable = ingredient("WRAP", "1.10");
    unavailable.setAvailable(false);
    when(ingredientRepo.findById("WRAP")).thenReturn(Mono.just(unavailable));

    StepVerifier.create(service.price(order(1, "WRAP")))
        .expectErrorMatches(error -> error instanceof OrderPricingException
            && "ORDER_INGREDIENT_UNAVAILABLE".equals(
                ((OrderPricingException) error).getCode()))
        .verify();
  }

  private TacoOrder order(int quantity, String... ingredientIds) {
    Taco taco = new Taco();
    taco.setName("Priced taco");
    taco.setIngredients(Arrays.stream(ingredientIds)
        .map(id -> new Ingredient(id, null, null))
        .collect(java.util.stream.Collectors.toList()));
    OrderLine line = new OrderLine();
    line.setTaco(taco);
    line.setQuantity(quantity);
    TacoOrder order = new TacoOrder();
    order.setItems(Collections.singletonList(line));
    return order;
  }

  private Ingredient ingredient(String id, String price) {
    return new Ingredient(id, id + " ingredient", Ingredient.Type.WRAP,
        new BigDecimal(price), true, 20, 5);
  }
}
