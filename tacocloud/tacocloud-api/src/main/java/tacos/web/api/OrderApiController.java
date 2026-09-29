package tacos.web.api;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.OrderMapper;

@RestController
@RequestMapping(path = "/api/orders", produces = "application/json")
public class OrderApiController {

  private final OrderService orderService;
  private final OrderMapper orderMapper;

  public OrderApiController(OrderService orderService, OrderMapper orderMapper) {
    this.orderService = orderService;
    this.orderMapper = orderMapper;
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrder(
      @Valid @RequestBody OrderCreateRequest request,
      Authentication authentication) {
    TacoOrder order = orderMapper.toEntity(request);
    return orderService.createOrder(order, authentication)
        .map(orderMapper::toResponse);
  }

  @PostMapping(path = "fromEmail", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrderFromEmail(
      @Valid @RequestBody EmailOrder emailOrder,
      Authentication authentication) {
    return orderService.createFromEmail(emailOrder, authentication)
        .map(orderMapper::toResponse);
  }

  @PutMapping(path = "/{orderId}", consumes = "application/json")
  public Mono<ResponseEntity<OrderResponse>> putOrder(
      @PathVariable("orderId") String orderId,
      @Valid @RequestBody OrderReplaceRequest replacement,
      Authentication authentication) {
    return orderService.replaceOrder(orderId, replacement, authentication)
        .map(orderMapper::toResponse)
        .map(ResponseEntity::ok);
  }

  @PatchMapping(path = "/{orderId}", consumes = "application/json")
  public Mono<ResponseEntity<OrderResponse>> patchOrder(
      @PathVariable("orderId") String orderId,
      @Valid @RequestBody OrderPatchRequest patch,
      Authentication authentication) {
    return orderService.patchOrder(orderId, patch, authentication)
        .map(orderMapper::toResponse)
        .map(ResponseEntity::ok);
  }

  @DeleteMapping("/{orderId}")
  public Mono<ResponseEntity<Void>> deleteOrder(
      @PathVariable("orderId") String orderId,
      Authentication authentication) {
    return orderService.deleteOrder(orderId, authentication)
        .thenReturn(ResponseEntity.noContent().build());
  }
}
