package tacos.kitchenqueue;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderLine;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.api.dto.KitchenOrderResponse;
import tacos.workflow.OrderWorkflowService;

@Service
public class KitchenQueueService {
  private final ReactiveMongoTemplate mongo;
  private final OrderWorkflowService workflow;
  private final KitchenEtaCalculator eta;

  public KitchenQueueService(ReactiveMongoTemplate mongo,
      OrderWorkflowService workflow, KitchenEtaCalculator eta) {
    this.mongo = mongo;
    this.workflow = workflow;
    this.eta = eta;
  }

  public Flux<KitchenOrderResponse> queue(Authentication authentication) {
    return requireKitchen(authentication)
        .then(mongo.count(activeQuery(), TacoOrder.class))
        .flatMapMany(active -> mongo.find(createdQuery(), TacoOrder.class)
            .index()
            .map(position -> toResponse(position.getT2(),
                active + position.getT1())));
  }

  public Mono<KitchenOrderResponse> claim(Authentication authentication) {
    return workflow.claimNext(authentication)
        .flatMap(this::withCurrentEstimate);
  }

  public Mono<KitchenOrderResponse> advance(String orderId, OrderStatus target,
      Long expectedVersion, String reason, Authentication authentication) {
    return requireKitchen(authentication)
        .then(Mono.defer(() -> target == OrderStatus.PREPARING
            || target == OrderStatus.READY
                ? workflow.changeStatus(orderId, target, expectedVersion,
                    reason, authentication).flatMap(this::withCurrentEstimate)
                : Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY))));
  }

  private Mono<KitchenOrderResponse> withCurrentEstimate(TacoOrder order) {
    return mongo.count(activeQuery(), TacoOrder.class)
        .map(active -> toResponse(order,
            Math.max(0, active - (isActive(order) ? 1 : 0))));
  }

  private KitchenOrderResponse toResponse(TacoOrder order, long queuedAhead) {
    KitchenOrderResponse response = new KitchenOrderResponse();
    response.setId(order.getId());
    response.setPlacedAt(order.getPlacedAt());
    response.setStatus(order.getStatus());
    response.setVersion(order.getVersion());
    response.setStationId(order.getStationId());
    response.setCookId(order.getCookId());
    response.setEstimatedPrepMinutes(eta.estimate(queuedAhead, order));
    response.setItems(safe(order.getItems()).stream()
        .filter(line -> line != null && line.getTaco() != null)
        .map(this::toItem).collect(Collectors.toList()));
    return response;
  }

  private KitchenOrderResponse.Item toItem(OrderLine line) {
    KitchenOrderResponse.Item item = new KitchenOrderResponse.Item();
    item.setTacoName(line.getTaco().getName());
    item.setQuantity(line.getQuantity());
    item.setIngredientIds(safe(line.getTaco().getIngredients()).stream()
        .filter(ingredient -> ingredient != null)
        .map(Ingredient::getId).collect(Collectors.toList()));
    item.setIngredientNames(safe(line.getTaco().getIngredients()).stream()
        .filter(ingredient -> ingredient != null)
        .map(ingredient -> ingredient.getName() == null
            ? ingredient.getId() : ingredient.getName())
        .collect(Collectors.toList()));
    return item;
  }

  private Query createdQuery() {
    return Query.query(where("status").is(OrderStatus.CREATED))
        .with(Sort.by(Sort.Direction.ASC, "placedAt", "_id"));
  }

  private Query activeQuery() {
    return Query.query(where("status").in(OrderStatus.ACCEPTED,
        OrderStatus.PREPARING));
  }

  private boolean isActive(TacoOrder order) {
    return order.getStatus() == OrderStatus.ACCEPTED
        || order.getStatus() == OrderStatus.PREPARING;
  }

  private Mono<Void> requireKitchen(Authentication authentication) {
    return Mono.defer(() -> {
      if (authentication == null || !authentication.isAuthenticated()
          || authentication instanceof AnonymousAuthenticationToken) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      boolean kitchen = authentication.getAuthorities().stream()
          .anyMatch(authority -> "ROLE_KITCHEN".equals(
              authority.getAuthority()));
      return kitchen ? Mono.empty()
          : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
    });
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? Collections.emptyList() : values;
  }
}
