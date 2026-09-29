package tacos.workflow;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.OrderStatusChange;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;

@Service
public class OrderWorkflowService {
  private static final String KITCHEN = "ROLE_KITCHEN";
  private static final String ADMIN = "ROLE_ADMIN";
  private static final Set<String> PREPARATION = Set.of(KITCHEN, ADMIN);
  private static final Set<String> DELIVERY = Set.of(ADMIN);
  private static final Map<OrderStatus, Map<OrderStatus, Set<String>>> TRANSITIONS =
      Map.of(
          OrderStatus.CREATED, Map.of(OrderStatus.ACCEPTED, PREPARATION),
          OrderStatus.ACCEPTED, Map.of(OrderStatus.PREPARING, PREPARATION),
          OrderStatus.PREPARING, Map.of(OrderStatus.READY, PREPARATION),
          OrderStatus.READY, Map.of(OrderStatus.OUT_FOR_DELIVERY, DELIVERY),
          OrderStatus.OUT_FOR_DELIVERY,
              Map.of(OrderStatus.DELIVERED, DELIVERY));

  private final OrderRepository orders;
  private final InventoryService inventory;

  public OrderWorkflowService(OrderRepository orders,
      InventoryService inventory) {
    this.orders = orders;
    this.inventory = inventory;
  }

  public Mono<TacoOrder> changeStatus(String orderId, OrderStatus target,
      Long expectedVersion, String reason, Authentication authentication) {
    return Mono.defer(() -> {
      String role = operationalRole(authentication);
      if (role == null) {
        return Mono.error(accessError(authentication));
      }
      if (target == null || target == OrderStatus.CANCELLED
          || target == OrderStatus.PLACED || !StringUtils.hasText(reason)) {
        return Mono.error(new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY));
      }
      return orders.findById(orderId)
          .switchIfEmpty(Mono.error(new ResponseStatusException(
              HttpStatus.NOT_FOUND)))
          .flatMap(order -> {
            if (order.getStatus() == target) {
              return canManageTarget(target, role) ? Mono.just(order)
                  : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
            }
            Set<String> allowedRoles = TRANSITIONS
                .getOrDefault(order.getStatus(), Map.of()).get(target);
            if (allowedRoles == null) {
              return Mono.error(conflict());
            }
            if (!allowedRoles.contains(role)) {
              return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
            }
            if (!Objects.equals(order.getVersion(), expectedVersion)) {
              return Mono.error(conflict());
            }
            OrderStatus previous = order.getStatus();
            order.setStatus(target);
            append(order, previous, target, authentication.getName(), role,
                role.equals(KITCHEN) ? "KITCHEN_API" : "ADMIN_API", reason);
            return orders.save(order);
          });
    });
  }

  public Mono<TacoOrder> cancel(String orderId, Long expectedVersion,
      String reason, Authentication authentication) {
    return Mono.defer(() -> {
      if (!authenticated(authentication)) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      if (!hasRole(authentication, "ROLE_USER")) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
      }
      if (!StringUtils.hasText(reason)) {
        return Mono.error(new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY));
      }
      return orders.findById(orderId)
          .switchIfEmpty(Mono.error(new ResponseStatusException(
              HttpStatus.NOT_FOUND)))
          .flatMap(order -> {
            if (order.getUser() == null
                || !authentication.getName().equals(
                    order.getUser().getUsername())) {
              return Mono.error(new ResponseStatusException(
                  HttpStatus.FORBIDDEN));
            }
            if (order.getStatus() == OrderStatus.CANCELLED) {
              return inventory.release(order.getInventoryReservationId())
                  .thenReturn(order);
            }
            if (!canCancel(order.getStatus())
                || !Objects.equals(order.getVersion(), expectedVersion)) {
              return Mono.error(conflict());
            }
            OrderStatus previous = order.getStatus();
            order.setStatus(OrderStatus.CANCELLED);
            append(order, previous, OrderStatus.CANCELLED,
                authentication.getName(), "ROLE_USER", "CUSTOMER_API", reason);
            return orders.save(order)
                .flatMap(saved -> inventory
                    .release(saved.getInventoryReservationId())
                    .thenReturn(saved));
          });
    });
  }

  static boolean allows(OrderStatus from, OrderStatus to, String role) {
    return TRANSITIONS.getOrDefault(from, Map.of())
        .getOrDefault(to, Set.of()).contains(role);
  }

  static boolean canCancel(OrderStatus status) {
    return status == OrderStatus.CREATED || status == OrderStatus.ACCEPTED;
  }

  private boolean canManageTarget(OrderStatus target, String role) {
    return TRANSITIONS.values().stream().anyMatch(next ->
        next.getOrDefault(target, Set.of()).contains(role));
  }

  private void append(TacoOrder order, OrderStatus from, OrderStatus to,
      String actorId, String actorRole, String origin, String reason) {
    List<OrderStatusChange> history = new ArrayList<>(
        order.getStatusHistory() == null ? List.of() : order.getStatusHistory());
    long previous = history.isEmpty() ? 0
        : history.get(history.size() - 1).getChangedAt().getTime();
    Date changedAt = new Date(Math.max(System.currentTimeMillis(), previous + 1));
    history.add(new OrderStatusChange(from, to, actorId, actorRole,
        changedAt, origin, reason.trim()));
    order.setStatusHistory(history);
  }

  private String operationalRole(Authentication authentication) {
    if (!authenticated(authentication)) {
      return null;
    }
    return hasRole(authentication, ADMIN) ? ADMIN
        : hasRole(authentication, KITCHEN) ? KITCHEN : null;
  }

  private ResponseStatusException accessError(Authentication authentication) {
    return new ResponseStatusException(authenticated(authentication)
        ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED);
  }

  private boolean authenticated(Authentication authentication) {
    return authentication != null && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken);
  }

  private boolean hasRole(Authentication authentication, String role) {
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> role.equals(authority.getAuthority()));
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT);
  }
}
