package tacos.history;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderHistoryPageResponse;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.OrderMapper;
import tacos.data.UserRepository;

@Service
public class OrderHistoryService {
  private static final Sort HISTORY_SORT = Sort.by(
      Sort.Order.desc("placedAt"), Sort.Order.asc("_id"));

  private final ReactiveMongoTemplate mongo;
  private final UserRepository userRepo;
  private final OrderMapper orderMapper;
  private final int maxPageSize;

  public OrderHistoryService(ReactiveMongoTemplate mongo,
      UserRepository userRepo, OrderMapper orderMapper,
      @Value("${tacocloud.order-history.max-page-size:50}") int maxPageSize) {
    if (maxPageSize < 1) {
      throw new IllegalArgumentException("max-page-size must be positive.");
    }
    this.mongo = mongo;
    this.userRepo = userRepo;
    this.orderMapper = orderMapper;
    this.maxPageSize = maxPageSize;
  }

  public Mono<OrderHistoryPageResponse> myOrders(int page, int size,
      Authentication authentication) {
    return Mono.defer(() -> {
      validatePaging(page, size);
      return currentUser(authentication)
          .flatMap(user -> page(Query.query(owner(user.getId())), page, size));
    });
  }

  public Mono<OrderResponse> myOrder(String orderId,
      Authentication authentication) {
    return currentUser(authentication)
        .flatMap(user -> mongo.findOne(Query.query(owner(user.getId())
            .and("_id").is(orderId)), TacoOrder.class))
        .switchIfEmpty(notFound())
        .map(orderMapper::toResponse);
  }

  public Mono<OrderHistoryPageResponse> adminOrders(int page, int size,
      String userId, OrderStatus status, Authentication authentication) {
    return Mono.defer(() -> {
      validatePaging(page, size);
      return requireAdmin(authentication)
          .then(Mono.defer(() -> {
            Query query = new Query();
            if (StringUtils.hasText(userId)) {
              query.addCriteria(owner(userId));
            }
            if (status != null) {
              query.addCriteria(Criteria.where("status").is(status));
            }
            return page(query, page, size);
          }));
    });
  }

  public Mono<OrderResponse> adminOrder(String orderId,
      Authentication authentication) {
    return requireAdmin(authentication)
        .then(mongo.findById(orderId, TacoOrder.class))
        .switchIfEmpty(notFound())
        .map(orderMapper::toResponse);
  }

  private Mono<OrderHistoryPageResponse> page(Query filter,
      int page, int size) {
    Query paged = Query.of(filter).with(HISTORY_SORT)
        .skip((long) page * size).limit(size);
    return Mono.zip(mongo.find(paged, TacoOrder.class)
            .map(orderMapper::toSummary).collectList(),
        mongo.count(filter, TacoOrder.class))
        .map(result -> {
          long total = result.getT2();
          long totalPages = total / size + (total % size == 0 ? 0 : 1);
          return new OrderHistoryPageResponse(result.getT1(), page, size,
              total, totalPages);
        });
  }

  private Criteria owner(String userId) {
    return Criteria.where("user._id").is(userId);
  }

  private void validatePaging(int page, int size) {
    if (page < 0 || size < 1 || size > maxPageSize) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "page o size fuera de rango.");
    }
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
          .filter(user -> StringUtils.hasText(user.getId()))
          .switchIfEmpty(Mono.error(
              new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    });
  }

  private Mono<Void> requireAdmin(Authentication authentication) {
    return Mono.defer(() -> {
      if (!isAuthenticated(authentication)) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      return hasRole(authentication, "ROLE_ADMIN")
          ? Mono.empty()
          : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
    });
  }

  private boolean isAuthenticated(Authentication authentication) {
    return authentication != null && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken);
  }

  private boolean hasRole(Authentication authentication, String role) {
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> role.equals(authority.getAuthority()));
  }

  private <T> Mono<T> notFound() {
    return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
