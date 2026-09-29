package tacos.api.mapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.OrderLine;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.dto.OrderSummaryResponse;

@Component
public class OrderMapper {

  public TacoOrder toEntity(OrderCreateRequest request) {
    TacoOrder order = new TacoOrder();
    order.setDeliveryName(request.getDeliveryName());
    order.setDeliveryStreet(request.getDeliveryStreet());
    order.setDeliveryCity(request.getDeliveryCity());
    order.setDeliveryState(request.getDeliveryState());
    order.setDeliveryZip(request.getDeliveryZip());
    order.setPaymentMethodId(request.getPaymentMethodId());
    order.setCouponCode(request.getCouponCode());
    order.setItems(safe(request.getItems()).stream()
        .map(this::toEntityLine)
        .collect(Collectors.toList()));
    return order;
  }

  public OrderResponse toResponse(TacoOrder order) {
    OrderResponse response = new OrderResponse();
    response.setId(order.getId());
    response.setPlacedAt(order.getPlacedAt());
    response.setStatus(order.getStatus());
    response.setUserId(order.getUser() == null ? null : order.getUser().getId());
    response.setDeliveryName(order.getDeliveryName());
    response.setDeliveryStreet(order.getDeliveryStreet());
    response.setDeliveryCity(order.getDeliveryCity());
    response.setDeliveryState(order.getDeliveryState());
    response.setDeliveryZip(order.getDeliveryZip());
    response.setPaymentBrand(order.getPaymentBrand());
    response.setPaymentLast4(order.getPaymentLast4());
    response.setCurrency(order.getCurrency());
    response.setSubtotal(order.getSubtotal());
    response.setCouponApplied(order.isCouponApplied());
    response.setDiscount(order.getDiscount());
    response.setTotal(order.getTotal());
    response.setItems(responseLines(order).stream()
        .map(this::toResponseLine)
        .collect(Collectors.toList()));
    return response;
  }

  public OrderSummaryResponse toSummary(TacoOrder order) {
    return new OrderSummaryResponse(order.getId(), order.getPlacedAt(),
        order.getStatus(), order.getTotal(), order.getCurrency());
  }

  public OrderCreateRequest toReorderRequest(TacoOrder source,
      String paymentMethodId, String couponCode) {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName(source.getDeliveryName());
    request.setDeliveryStreet(source.getDeliveryStreet());
    request.setDeliveryCity(source.getDeliveryCity());
    request.setDeliveryState(source.getDeliveryState());
    request.setDeliveryZip(source.getDeliveryZip());
    request.setPaymentMethodId(paymentMethodId);
    request.setCouponCode(couponCode);
    request.setItems(responseLines(source).stream().map(line -> {
      OrderCreateRequest.OrderItem item = new OrderCreateRequest.OrderItem();
      OrderCreateRequest.TacoItem taco = new OrderCreateRequest.TacoItem();
      if (line != null && line.getTaco() != null) {
        taco.setName(line.getTaco().getName());
        taco.setIngredientIds(safe(line.getTaco().getIngredients()).stream()
            .map(ingredient -> ingredient == null ? null : ingredient.getId())
            .collect(Collectors.toList()));
        item.setTaco(taco);
        item.setQuantity(line.getQuantity());
      }
      return item;
    }).collect(Collectors.toList()));
    return request;
  }

  private OrderLine toEntityLine(OrderCreateRequest.OrderItem request) {
    OrderLine line = new OrderLine();
    if (request != null) {
      line.setTaco(toEntityTaco(request.getTaco()));
      line.setQuantity(request.getQuantity() == null ? 0 : request.getQuantity());
    }
    return line;
  }

  private Taco toEntityTaco(OrderCreateRequest.TacoItem request) {
    Taco taco = new Taco();
    if (request != null) {
      taco.setName(request.getName());
      taco.setIngredients(safe(request.getIngredientIds()).stream()
          .map(id -> new Ingredient(id, null, null))
          .collect(Collectors.toList()));
    }
    return taco;
  }

  private OrderResponse.OrderItem toResponseLine(OrderLine line) {
    OrderResponse.OrderItem response = new OrderResponse.OrderItem();
    if (line == null) {
      response.setTaco(toResponseTaco(null));
      return response;
    }
    response.setTaco(toResponseTaco(line.getTaco()));
    response.setQuantity(line.getQuantity());
    response.setUnitPriceAtPurchase(line.getUnitPriceAtPurchase());
    response.setSubtotal(line.getSubtotal());
    return response;
  }

  private OrderResponse.TacoItem toResponseTaco(Taco taco) {
    OrderResponse.TacoItem response = new OrderResponse.TacoItem();
    if (taco == null) {
      response.setIngredientIds(Collections.emptyList());
      return response;
    }
    response.setId(taco.getId());
    response.setName(taco.getName());
    response.setIngredientIds(safe(taco.getIngredients()).stream()
        .map(ingredient -> ingredient == null ? null : ingredient.getId())
        .collect(Collectors.toList()));
    return response;
  }

  private List<OrderLine> responseLines(TacoOrder order) {
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
