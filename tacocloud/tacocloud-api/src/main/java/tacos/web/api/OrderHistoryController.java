package tacos.web.api;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.api.dto.OrderHistoryPageResponse;
import tacos.api.dto.OrderResponse;
import tacos.history.OrderHistoryService;

@RestController
@RequestMapping(produces = "application/json")
public class OrderHistoryController {
  private final OrderHistoryService history;

  public OrderHistoryController(OrderHistoryService history) {
    this.history = history;
  }

  @GetMapping({"/api/orders/me", "/api/v1/orders/me"})
  public Mono<OrderHistoryPageResponse> myOrders(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      Authentication authentication) {
    return history.myOrders(page, size, authentication);
  }

  @GetMapping({"/api/orders/me/{id}", "/api/v1/orders/me/{id}"})
  public Mono<OrderResponse> myOrder(@PathVariable String id,
      Authentication authentication) {
    return history.myOrder(id, authentication);
  }

  @GetMapping({"/api/admin/orders", "/api/v1/admin/orders"})
  public Mono<OrderHistoryPageResponse> adminOrders(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String userId,
      @RequestParam(required = false) OrderStatus status,
      Authentication authentication) {
    return history.adminOrders(page, size, userId, status, authentication);
  }

  @GetMapping({"/api/admin/orders/{id}", "/api/v1/admin/orders/{id}"})
  public Mono<OrderResponse> adminOrder(@PathVariable String id,
      Authentication authentication) {
    return history.adminOrder(id, authentication);
  }
}
