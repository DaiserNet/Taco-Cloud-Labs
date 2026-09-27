package tacos.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderLine;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.IngredientRepository;

@Service
public class OrderPricingService {

  private static final int MONEY_SCALE = 2;

  private final IngredientRepository ingredientRepo;
  private final int maxQuantity;
  private final String currency;

  public OrderPricingService(IngredientRepository ingredientRepo,
      @Value("${tacocloud.order-pricing.max-quantity:10}") int maxQuantity,
      @Value("${tacocloud.order-pricing.currency:USD}") String currency) {
    if (maxQuantity < 1) {
      throw new IllegalArgumentException("Maximum order quantity must be positive.");
    }
    this.ingredientRepo = ingredientRepo;
    this.maxQuantity = maxQuantity;
    this.currency = Currency.getInstance(currency).getCurrencyCode();
  }

  public Mono<TacoOrder> price(TacoOrder order) {
    List<OrderLine> requestedLines = requestedLines(order);
    if (requestedLines.isEmpty()) {
      return Mono.error(new OrderPricingException(
          "ORDER_ITEMS_REQUIRED", "At least one order item is required."));
    }

    return Flux.fromIterable(requestedLines)
        .concatMap(this::priceLine)
        .collectList()
        .map(pricedLines -> {
          BigDecimal subtotal = pricedLines.stream()
              .map(OrderLine::getSubtotal)
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
          order.setItems(pricedLines);
          order.setCurrency(currency);
          order.setSubtotal(subtotal);
          order.setTotal(subtotal);
          return order;
        });
  }

  private Mono<OrderLine> priceLine(OrderLine line) {
    if (line == null || line.getTaco() == null) {
      return Mono.error(new OrderPricingException(
          "ORDER_TACO_REQUIRED", "Each order item must contain a taco."));
    }
    if (line.getQuantity() < 1 || line.getQuantity() > maxQuantity) {
      return Mono.error(new OrderPricingException(
          "ORDER_QUANTITY_INVALID",
          "Quantity must be between 1 and " + maxQuantity + "."));
    }

    Taco taco = line.getTaco();
    List<Ingredient> ingredientReferences = safe(taco.getIngredients());
    if (ingredientReferences.isEmpty()) {
      return Mono.error(new OrderPricingException(
          "ORDER_INGREDIENTS_REQUIRED",
          "Each taco must contain at least one ingredient."));
    }

    return Flux.fromIterable(ingredientReferences)
        .concatMap(this::resolveSellableIngredient)
        .collectList()
        .map(ingredients -> {
          BigDecimal unitPrice = ingredients.stream()
              .map(Ingredient::getUnitPrice)
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
          BigDecimal lineSubtotal = unitPrice
              .multiply(BigDecimal.valueOf(line.getQuantity()))
              .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
          taco.setIngredients(ingredients);
          line.setUnitPriceAtPurchase(unitPrice);
          line.setSubtotal(lineSubtotal);
          return line;
        });
  }

  private Mono<Ingredient> resolveSellableIngredient(Ingredient reference) {
    String ingredientId = reference == null ? null : reference.getId();
    if (!StringUtils.hasText(ingredientId)) {
      return Mono.error(new OrderPricingException(
          "ORDER_INGREDIENT_ID_REQUIRED", "Ingredient ID must be provided."));
    }
    return ingredientRepo.findById(ingredientId)
        .switchIfEmpty(Mono.error(new OrderPricingException(
            "ORDER_INGREDIENT_NOT_FOUND",
            "Unknown ingredient: " + ingredientId)))
        .flatMap(ingredient -> {
          if (!ingredient.isAvailable() || ingredient.getStockOnHand() < 1) {
            return Mono.error(new OrderPricingException(
                "ORDER_INGREDIENT_UNAVAILABLE",
                "Ingredient is not available: " + ingredientId));
          }
          if (ingredient.getUnitPrice() == null
              || ingredient.getUnitPrice().signum() < 0) {
            return Mono.error(new OrderPricingException(
                "ORDER_INGREDIENT_PRICE_INVALID",
                "Ingredient has no valid price: " + ingredientId));
          }
          return Mono.just(ingredient);
        });
  }

  private List<OrderLine> requestedLines(TacoOrder order) {
    if (order.getItems() != null && !order.getItems().isEmpty()) {
      return order.getItems();
    }
    return safe(order.getTacos()).stream()
        .map(taco -> {
          OrderLine line = new OrderLine();
          line.setTaco(taco);
          line.setQuantity(1);
          return line;
        })
        .collect(Collectors.toList());
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? Collections.emptyList() : values;
  }
}
