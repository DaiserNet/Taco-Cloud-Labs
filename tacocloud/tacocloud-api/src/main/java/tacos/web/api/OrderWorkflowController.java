package tacos.web.api;

import javax.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.OrderCancelRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.dto.OrderStatusRequest;
import tacos.api.mapper.OrderMapper;
import tacos.workflow.OrderWorkflowService;

@RestController
@RequestMapping(path = {"/api/orders/{orderId}", "/api/v1/orders/{orderId}"}, produces = "application/json")
public class OrderWorkflowController {
  private final OrderWorkflowService workflow;
  private final OrderMapper mapper;

  public OrderWorkflowController(OrderWorkflowService workflow,
      OrderMapper mapper) {
    this.workflow = workflow;
    this.mapper = mapper;
  }

  @PatchMapping(path = "/status", consumes = "application/json")
  public Mono<OrderResponse> status(@PathVariable String orderId,
      @Valid @RequestBody OrderStatusRequest request,
      Authentication authentication) {
    return workflow.changeStatus(orderId, request.getStatus(),
        request.getExpectedVersion(), request.getReason(), authentication)
        .map(mapper::toResponse);
  }

  @PostMapping(path = "/cancel", consumes = "application/json")
  public Mono<OrderResponse> cancel(@PathVariable String orderId,
      @Valid @RequestBody OrderCancelRequest request,
      Authentication authentication) {
    return workflow.cancel(orderId, request.getExpectedVersion(),
        request.getReason(), authentication).map(mapper::toResponse);
  }
}
