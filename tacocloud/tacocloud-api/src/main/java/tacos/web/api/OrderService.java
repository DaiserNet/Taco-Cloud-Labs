package tacos.web.api;

import java.util.Date;
import java.util.Set;
import java.util.function.Function;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.OrderStatusChange;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.observability.OrderMetrics;
import tacos.payment.PaymentMethodService;
import tacos.pricing.CouponService;
import tacos.pricing.OrderPricingService;

@Service
public class OrderService {

  private final OrderRepository repo;
  private final EmailOrderService emailOrderService;
  private final OrderOutboxService outbox;
  private final UserRepository userRepo;
  private final Validator validator;
  private final PaymentMethodService paymentMethodService;
  private final OrderPricingService orderPricingService;
  private final CouponService couponService;
  private final InventoryService inventoryService;
  private final OrderMetrics metrics;

  public OrderService(OrderRepository repo, EmailOrderService emailOrderService,
      OrderOutboxService outbox, UserRepository userRepo,
      Validator validator, PaymentMethodService paymentMethodService,
      OrderPricingService orderPricingService, CouponService couponService,
      InventoryService inventoryService, OrderMetrics metrics) {
    this.repo = repo;
    this.emailOrderService = emailOrderService;
    this.outbox = outbox;
    this.userRepo = userRepo;
    this.validator = validator;
    this.paymentMethodService = paymentMethodService;
    this.orderPricingService = orderPricingService;
    this.couponService = couponService;
    this.inventoryService = inventoryService;
    this.metrics = metrics;
  }

  public Mono<TacoOrder> createOrder(
      TacoOrder order, Authentication authentication) {
    return createOrder(order, authentication, priced -> Mono.empty());
  }

  public Mono<TacoOrder> createOrder(TacoOrder order,
      Authentication authentication,
      Function<TacoOrder, Mono<Void>> beforeReservation) {
    return createOrder(order, authentication, beforeReservation, "HTTP_API");
  }

  public Mono<TacoOrder> createOrder(TacoOrder order,
      Authentication authentication,
      Function<TacoOrder, Mono<Void>> beforeReservation, String origin) {
    return createOrder(order, authentication, beforeReservation, origin,
        saved -> Mono.empty());
  }

  public Mono<TacoOrder> createOrder(TacoOrder order,
      Authentication authentication,
      Function<TacoOrder, Mono<Void>> beforeReservation, String origin,
      Function<TacoOrder, Mono<Void>> afterOutbox) {
    return metrics.placement(currentUser(authentication)
        .flatMap(user -> paymentMethodService
            .findOwned(order.getPaymentMethodId(), authentication)
            .flatMap(payment -> orderPricingService.price(order)
                .flatMap(couponService::apply)
                .flatMap(pricedOrder -> {
                  pricedOrder.setUser(user);
                  pricedOrder.setPaymentBrand(payment.getBrand());
                  pricedOrder.setPaymentLast4(payment.getLast4());
                  return Mono.defer(() -> beforeReservation.apply(pricedOrder))
                      .then(Mono.defer(() -> {
                        initializeLifecycle(pricedOrder, authentication, origin);
                        return reserveSaveAndAccept(pricedOrder, afterOutbox);
                      }));
                }))), origin);
  }

  public Mono<TacoOrder> createFromEmail(
      EmailOrder emailOrder, Authentication authentication) {
    return metrics.placement(Mono.defer(() -> {
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
        .flatMap(couponService::apply)
        .flatMap(order -> {
          initializeLifecycle(order, authentication, "EMAIL");
          return reserveSaveAndAccept(order);
        }), "EMAIL");
  }

  public Mono<TacoOrder> patchOrder(
      String orderId, OrderPatchRequest patch, Authentication authentication) {
    return findAuthorizedEditable(orderId, authentication)
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
    return findAuthorized(orderId, authentication)
        .flatMap(order -> order.getStatus() == OrderStatus.PLACED
            ? inventoryService.release(order.getInventoryReservationId())
                .then(repo.deleteById(orderId))
            : Mono.error(new ResponseStatusException(HttpStatus.CONFLICT)));
  }

  private void initializeLifecycle(TacoOrder order,
      Authentication authentication, String origin) {
    order.setStatus(OrderStatus.CREATED);
    order.setStatusHistory(java.util.Collections.singletonList(
        new OrderStatusChange(null, OrderStatus.CREATED,
            authentication.getName(),
            hasRole(authentication, "ROLE_ADMIN") ? "ROLE_ADMIN" : "ROLE_USER",
            new Date(), origin,
            "Order created")));
  }

  private Mono<TacoOrder> reserveSaveAndAccept(TacoOrder order) {
    return reserveSaveAndAccept(order, saved -> Mono.empty());
  }

  private Mono<TacoOrder> reserveSaveAndAccept(TacoOrder order,
      Function<TacoOrder, Mono<Void>> afterOutbox) {
    return inventoryService.reserve(order)
        .flatMap(reservation -> saveReservedOrder(order, reservation, afterOutbox));
  }

  private Mono<TacoOrder> saveReservedOrder(
      TacoOrder order, InventoryReservation reservation,
      Function<TacoOrder, Mono<Void>> afterOutbox) {
    return outbox.saveAcceptedOrder(order, reservation, afterOutbox)
        .onErrorResume(error -> releaseAndPropagate(reservation, error));
  }

  private <T> Mono<T> releaseAndPropagate(
      InventoryReservation reservation, Throwable error) {
    return inventoryService.release(reservation.getId())
        .then(Mono.error(error));
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
    return order.getStatus() == OrderStatus.CREATED
        || order.getStatus() == OrderStatus.PLACED;
  }
}
