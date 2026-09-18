package tacos.api.mapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;

@Component
public class OrderMapper {

  public TacoOrder toEntity(OrderCreateRequest request) {
    TacoOrder order = new TacoOrder();
    order.setDeliveryName(request.getDeliveryName());
    order.setDeliveryStreet(request.getDeliveryStreet());
    order.setDeliveryCity(request.getDeliveryCity());
    order.setDeliveryState(request.getDeliveryState());
    order.setDeliveryZip(request.getDeliveryZip());
    order.setCcNumber(request.getCcNumber());
    order.setCcExpiration(request.getCcExpiration());
    order.setCcCVV(request.getCcCVV());
    order.setTacos(safe(request.getTacos()).stream()
        .map(this::toEntityTaco)
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
    response.setPaymentLast4(lastFour(order.getCcNumber()));
    response.setTacos(safe(order.getTacos()).stream()
        .map(this::toResponseTaco)
        .collect(Collectors.toList()));
    return response;
  }

  private Taco toEntityTaco(OrderCreateRequest.TacoItem request) {
    Taco taco = new Taco();
    if (request != null) {
      taco.setId(request.getId());
      taco.setName(request.getName());
      taco.setIngredients(safe(request.getIngredients()).stream()
          .map(ingredient -> new Ingredient(
              ingredient == null ? null : ingredient.getId(), null, null))
          .collect(Collectors.toList()));
    }
    return taco;
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

  private String lastFour(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 4 ? value : value.substring(value.length() - 4);
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? Collections.emptyList() : values;
  }
}
