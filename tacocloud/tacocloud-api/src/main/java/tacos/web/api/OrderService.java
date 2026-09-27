package tacos.web.api;

import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.payment.PaymentMethodService;
import tacos.pricing.OrderPricingService;

@Service
public class OrderService {

  private final OrderRepository repo;
  private final EmailOrderService emailOrderService;
  private final OrderMessagingService orderMessages;
  private final UserRepository userRepo;
  private final Validator validator;
  private final PaymentMethodService paymentMethodService;
  private final OrderPricingService orderPricingService;

  public OrderService(OrderRepository repo, EmailOrderService emailOrderService,
      OrderMessagingService orderMessages, UserRepository userRepo,
      Validator validator, PaymentMethodService paymentMethodService,
      OrderPricingService orderPricingService) {
    this.repo = repo;
    this.emailOrderService = emailOrderService;
    this.orderMessages = orderMessages;
    this.userRepo = userRepo;
    this.validator = validator;
    this.paymentMethodService = paymentMethodService;
    this.orderPricingService = orderPricingService;
  }

  public Flux<TacoOrder> findVisibleOrders(Authentication authentication) {
    return Flux.defer(() -> {
      if (!isAuthenticated(authentication)) {
        return Flux.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      if (hasRole(authentication, "ROLE_ADMIN")) {
        return repo.findAll();
      }
      if (hasRole(authentication, "ROLE_USER")) {
        return repo.findByUserUsernameOrderByPlacedAtDesc(authentication.getName());
      }
      return Flux.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
    });
  }

  public Mono<TacoOrder> createOrder(
      TacoOrder order, Authentication authentication) {
    return currentUser(authentication)
        .flatMap(user -> paymentMethodService
            .findOwned(order.getPaymentMethodId(), authentication)
            .flatMap(payment -> orderPricingService.price(order)
                .flatMap(pricedOrder -> {
                  pricedOrder.setUser(user);
                  pricedOrder.setPaymentBrand(payment.getBrand());
                  pricedOrder.setPaymentLast4(payment.getLast4());
                  return Mono.fromRunnable(
                      () -> orderMessages.sendOrder(pricedOrder))
                      .then(Mono.defer(() -> repo.save(pricedOrder)));
                })));
  }

  public Mono<TacoOrder> createFromEmail(
      EmailOrder emailOrder, Authentication authentication) {
    return Mono.defer(() -> {
      if (!isAuthenticated(authentication)) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      if (!hasRole(authentication, "ROLE_USER")
          && !hasRole(authentication, "ROLE_ADMIN")) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
      }
      return emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));
    })
        .flatMap(order -> isOwnerOrAdmin(order, authentication)
            ? Mono.just(order)
            : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)))
        .flatMap(orderPricingService::price)
        .flatMap(repo::save)
        .flatMap(savedOrder -> Mono.fromRunnable(
            () -> orderMessages.sendOrder(savedOrder))
            .thenReturn(savedOrder));
  }

  public Mono<TacoOrder> patchOrder(
      String orderId, OrderPatchRequest patch, Authentication authentication) {
    return findAuthorized(orderId, authentication)
        .flatMap(order -> {
          patch.applyTo(order);
          Set<ConstraintViolation<TacoOrder>> violations = validator.validate(order);
          return violations.isEmpty()
              ? repo.save(order)
              : Mono.error(new ResponseStatusException(
                  HttpStatus.UNPROCESSABLE_ENTITY));
        });
  }

  public Mono<TacoOrder> replaceOrder(String orderId,
      OrderReplaceRequest replacement, Authentication authentication) {
    return findAuthorizedEditable(orderId, authentication)
        .map(order -> {
          replacement.applyTo(order);
          return order;
        })
        .flatMap(repo::save);
  }

  public Mono<Void> deleteOrder(String orderId, Authentication authentication) {
    return findAuthorizedEditable(orderId, authentication)
        .flatMap(order -> repo.deleteById(orderId));
  }

  private Mono<TacoOrder> findAuthorizedEditable(
      String orderId, Authentication authentication) {
    return findAuthorized(orderId, authentication)
        .flatMap(order -> isEditable(order)
            ? Mono.just(order)
            : Mono.error(new ResponseStatusException(HttpStatus.CONFLICT)));
  }

  private Mono<TacoOrder> findAuthorized(
      String orderId, Authentication authentication) {
    return Mono.defer(() -> {
      if (!isAuthenticated(authentication)) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      if (!hasRole(authentication, "ROLE_USER")
          && !hasRole(authentication, "ROLE_ADMIN")) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
      }
      return repo.findById(orderId)
          .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
          .flatMap(order -> isOwnerOrAdmin(order, authentication)
              ? Mono.just(order)
              : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
    });
  }

  private Mono<User> currentUser(Authentication authentication) {
    return Mono.defer(() -> {
      if (!isAuthenticated(authentication)) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      if (!hasRole(authentication, "ROLE_USER")) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
      }
      return userRepo.findByUsername(authentication.getName())
          .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    });
  }

  private boolean isOwnerOrAdmin(
      TacoOrder order, Authentication authentication) {
    if (!isAuthenticated(authentication)) {
      return false;
    }
    User owner = order.getUser();
    return hasRole(authentication, "ROLE_ADMIN")
        || (hasRole(authentication, "ROLE_USER") && owner != null
            && authentication.getName().equals(owner.getUsername()));
  }

  private boolean isAuthenticated(Authentication authentication) {
    return authentication != null && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken);
  }

  private boolean hasRole(Authentication authentication, String role) {
    return isAuthenticated(authentication) && authentication.getAuthorities().stream()
        .anyMatch(authority -> role.equals(authority.getAuthority()));
  }

  private boolean isEditable(TacoOrder order) {
    return order.getStatus() == null || order.getStatus() == OrderStatus.PLACED;
  }
}
