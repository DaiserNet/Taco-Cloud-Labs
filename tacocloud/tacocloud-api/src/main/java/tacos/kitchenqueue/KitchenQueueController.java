package tacos.kitchenqueue;

import javax.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.api.dto.KitchenOrderResponse;
import tacos.api.dto.OrderStatusRequest;

@RestController
@RequestMapping(path = "/api/kitchen", produces = "application/json")
public class KitchenQueueController {
  private final KitchenQueueService queue;

  public KitchenQueueController(KitchenQueueService queue) {
    this.queue = queue;
  }

  @GetMapping("/queue")
  public Flux<KitchenOrderResponse> queue(Authentication authentication) {
    return queue.queue(authentication);
  }

  @PostMapping("/claim")
  public Mono<ResponseEntity<KitchenOrderResponse>> claim(
      Authentication authentication) {
    return queue.claim(authentication).map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.noContent().build());
  }

  @PatchMapping(path = "/orders/{orderId}/status",
      consumes = "application/json")
  public Mono<KitchenOrderResponse> advance(@PathVariable String orderId,
      @Valid @RequestBody OrderStatusRequest request,
      Authentication authentication) {
    return queue.advance(orderId, request.getStatus(),
        request.getExpectedVersion(), request.getReason(), authentication);
  }
}
