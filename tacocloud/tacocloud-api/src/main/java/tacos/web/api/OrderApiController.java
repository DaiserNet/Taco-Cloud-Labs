package tacos.web.api;

import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Valid;
import javax.validation.Validator;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

@RestController
@RequestMapping(path="/api/orders",
                produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class OrderApiController {

  private OrderRepository repo;
  private OrderMessagingService orderMessages;
  private EmailOrderService emailOrderService;

  private Validator validator;

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            Validator validator) {
    this.repo = repo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
    this.validator = validator;
  }

  @GetMapping(produces="application/json")
  public Flux<TacoOrder> allOrders() {
    return repo.findAll();
  }

//  @PostMapping(consumes="application/json")
//  @ResponseStatus(HttpStatus.CREATED)
//  public Mono<Order> postOrder(@RequestBody Mono<Order> order) {
//    order.subscribe(orderMessages::sendOrder); // TODO: not ideal...work into reactive flow below
//    return order
//        .flatMap(repo::save);
//  }

  @PostMapping(consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<TacoOrder> postOrder(@RequestBody TacoOrder order) {
    orderMessages.sendOrder(order);
    return repo.save(order);
  }

  @PostMapping(path="fromEmail", consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<TacoOrder> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
    Mono<TacoOrder> order = emailOrderService.convertEmailOrderToDomainOrder(emailOrder);
    order.subscribe(orderMessages::sendOrder); // TODO: not ideal...work into reactive flow below
    return order
        .flatMap(repo::save);
  }

  @PutMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<TacoOrder>> putOrder(
      @PathVariable("orderId") String orderId,
      @Valid @RequestBody OrderReplaceRequest replacement,
      Authentication authentication) {
    if (isUnauthenticated(authentication)) {
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    return Mono.defer(() -> repo.findById(orderId)
        .flatMap(order -> {
          if (!isOwnerOrAdmin(order, authentication)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .<TacoOrder>build());
          }
          if (!isEditable(order)) {
            return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                .<TacoOrder>build());
          }

          replacement.applyTo(order);
          return repo.save(order).map(ResponseEntity::ok);
        }))
        .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
  }

  @PatchMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<TacoOrder>> patchOrder(@PathVariable("orderId") String orderId,
                          @Valid @RequestBody OrderPatchRequest patch,
                          Authentication authentication) {
    
    if (isUnauthenticated(authentication)) {
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).<TacoOrder>build());
    }
    
    return repo.findById(orderId).flatMap(order -> {
      if (!isOwnerOrAdmin(order, authentication)) {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<TacoOrder>build());
      }
      
      applyPatch(order, patch);
      Set<ConstraintViolation<TacoOrder>> violations = validator.validate(order);

      if(!violations.isEmpty()) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).<TacoOrder>build());
      }

      return repo.save(order).map(ResponseEntity::ok);
    }).switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
  }

  private void applyPatch(TacoOrder order, OrderPatchRequest patch) {
    if (patch.getDeliveryName() != null) {
      order.setDeliveryName(patch.getDeliveryName());
    }
    if (patch.getDeliveryStreet() != null) {
      order.setDeliveryStreet(patch.getDeliveryStreet());
    }
    if (patch.getDeliveryCity() != null) {
      order.setDeliveryCity(patch.getDeliveryCity());
    }
    if (patch.getDeliveryState() != null) {
      order.setDeliveryState(patch.getDeliveryState());
    }
    if (patch.getDeliveryZip() != null) {
      order.setDeliveryZip(patch.getDeliveryZip());
    }
  }
  
  private boolean isOwnerOrAdmin(TacoOrder order, Authentication authentication) {

    boolean isOwner =
      order.getUser() != null && order.getUser().getUsername() != null && order.getUser().getUsername().equals(authentication.getName());

    boolean isAdmin = authentication.getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

    return isOwner || isAdmin;
  }

  private boolean isUnauthenticated(Authentication authentication) {
    return authentication == null || authentication instanceof AnonymousAuthenticationToken;
  }

  private boolean isEditable(TacoOrder order) {
    return order.getStatus() == null || order.getStatus() == OrderStatus.PLACED;
  }

  @DeleteMapping("/{orderId}")
  public Mono<ResponseEntity<Void>> deleteOrder(
      @PathVariable("orderId") String orderId, Authentication authentication) {
    if (isUnauthenticated(authentication)) {
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    return Mono.defer(() -> repo.findById(orderId)
        .flatMap(order -> {
          if (!isOwnerOrAdmin(order, authentication)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<Void>build());
          }
          if (!isEditable(order)) {
            return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).<Void>build());
          }
          return repo.deleteById(orderId)
              .thenReturn(ResponseEntity.noContent().<Void>build());
        }))
        .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
  }

}
