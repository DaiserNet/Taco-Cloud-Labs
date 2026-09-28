package tacos.inventory;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderLine;
import tacos.Taco;
import tacos.TacoOrder;

@Service
public class InventoryService {

  private final ReactiveMongoTemplate mongo;

  public InventoryService(ReactiveMongoTemplate mongo) {
    this.mongo = mongo;
  }

  public Mono<InventoryReservation> reserve(TacoOrder order) {
    return Mono.defer(() -> {
      if (order == null) {
        return Mono.error(invalid("Order is required for inventory reservation."));
      }
      List<InventoryReservationItem> items = requestedItems(order);
      if (items.isEmpty()) {
        return Mono.error(invalid("At least one ingredient must be reserved."));
      }

      String reservationId = reservationId(order);
      InventoryReservation reservation = new InventoryReservation(
          reservationId, reservationId, order.getId(), items);
      return mongo.insert(reservation)
          .flatMap(this::reserveAll)
          .onErrorResume(DuplicateKeyException.class,
              error -> existingReservation(reservation));
    });
  }

  public Mono<Void> accept(String reservationId, String orderId) {
    return Mono.defer(() -> {
      if (!StringUtils.hasText(reservationId)
          || !StringUtils.hasText(orderId)) {
        return Mono.error(invalid(
            "Reservation ID and order ID are required for acceptance."));
      }
      Query query = Query.query(where("_id").is(reservationId)
          .and("status").is(InventoryReservationStatus.RESERVED));
      Update update = new Update()
          .set("status", InventoryReservationStatus.ACCEPTED)
          .set("orderId", orderId);
      return mongo.updateFirst(query, update, InventoryReservation.class)
          .flatMap(result -> result.getModifiedCount() == 1
              ? Mono.just(true)
              : acceptedAlready(reservationId, orderId))
          .then();
    });
  }

  public Mono<Void> release(String reservationId) {
    if (!StringUtils.hasText(reservationId)) {
      return Mono.empty();
    }
    return claimForRelease(reservationId)
        .switchIfEmpty(Mono.defer(() -> releasingAlready(reservationId)))
        .flatMap(reservation -> Flux.fromIterable(
                safe(reservation.getItems()))
            .concatMap(item -> releaseItem(reservationId, item))
            .then(markReleased(reservationId)))
        .then();
  }

  private Mono<InventoryReservation> reserveAll(
      InventoryReservation reservation) {
    return Flux.fromIterable(reservation.getItems())
        .concatMap(item -> reserveItem(reservation.getId(), item))
        .then(markReserved(reservation.getId()))
        .onErrorResume(error -> release(reservation.getId())
            .then(Mono.error(error)));
  }

  private Mono<Void> reserveItem(
      String reservationId, InventoryReservationItem item) {
    Query stockAvailable = Query.query(where("_id").is(item.getIngredientId())
        .and("available").is(true)
        .and("stockOnHand").gte(item.getQuantity())
        .and("inventoryReservationIds").ne(reservationId));
    Update decrement = new Update()
        .inc("stockOnHand", -item.getQuantity())
        .addToSet("inventoryReservationIds", reservationId);

    return mongo.findAndModify(stockAvailable, decrement,
            FindAndModifyOptions.options().returnNew(true), Ingredient.class)
        .switchIfEmpty(Mono.error(new InsufficientStockException(
            item.getIngredientId(), item.getQuantity())))
        .flatMap(ingredient -> recordReservedItem(reservationId, item));
  }

  private Mono<Void> recordReservedItem(
      String reservationId, InventoryReservationItem item) {
    Query reserving = Query.query(where("_id").is(reservationId)
        .and("status").is(InventoryReservationStatus.RESERVING));
    return mongo.updateFirst(reserving,
            new Update().addToSet("reservedItems", item),
            InventoryReservation.class)
        .flatMap(result -> result.getModifiedCount() == 1
            ? Mono.<Void>empty()
            : Mono.error(conflict(
                "Reservation changed while stock was being reserved.")));
  }

  private Mono<InventoryReservation> markReserved(String reservationId) {
    Query reserving = Query.query(where("_id").is(reservationId)
        .and("status").is(InventoryReservationStatus.RESERVING));
    return mongo.findAndModify(reserving,
            new Update().set("status", InventoryReservationStatus.RESERVED),
            FindAndModifyOptions.options().returnNew(true),
            InventoryReservation.class)
        .switchIfEmpty(Mono.error(conflict(
            "Reservation could not be marked as reserved.")));
  }

  private Mono<InventoryReservation> existingReservation(
      InventoryReservation requested) {
    return mongo.findById(requested.getId(), InventoryReservation.class)
        .switchIfEmpty(Mono.error(conflict(
            "Reservation identity already exists without a readable record.")))
        .flatMap(existing -> {
          if (!Objects.equals(existing.getItems(), requested.getItems())) {
            return Mono.error(conflict(
                "Reservation identity cannot be reused for different items."));
          }
          if (existing.getStatus() == InventoryReservationStatus.RESERVED
              || existing.getStatus() == InventoryReservationStatus.ACCEPTED) {
            return Mono.just(existing);
          }
          return Mono.error(conflict(
              "Reservation is not available for another attempt."));
        });
  }

  private Mono<InventoryReservation> claimForRelease(String reservationId) {
    Query releasable = Query.query(where("_id").is(reservationId)
        .and("status").in(InventoryReservationStatus.RESERVING,
            InventoryReservationStatus.RESERVED,
            InventoryReservationStatus.ACCEPTED));
    return mongo.findAndModify(releasable,
        new Update().set("status", InventoryReservationStatus.RELEASING),
        FindAndModifyOptions.options().returnNew(true),
        InventoryReservation.class);
  }

  private Mono<Void> releaseItem(
      String reservationId, InventoryReservationItem item) {
    Query ingredient = Query.query(where("_id").is(item.getIngredientId())
        .and("inventoryReservationIds").is(reservationId));
    Update restore = new Update()
        .inc("stockOnHand", item.getQuantity())
        .pull("inventoryReservationIds", reservationId);
    return mongo.updateFirst(ingredient, restore, Ingredient.class)
        .flatMap(result -> result.getMatchedCount() == 1
            ? Mono.<Void>empty()
            : mongo.exists(Query.query(where("_id").is(item.getIngredientId())),
                Ingredient.class)
                .flatMap(exists -> exists
                    ? Mono.<Void>empty()
                    : Mono.error(conflict(
                        "Reserved ingredient no longer exists: "
                            + item.getIngredientId()))));
  }

  private Mono<Void> markReleased(String reservationId) {
    Query releasing = Query.query(where("_id").is(reservationId)
        .and("status").is(InventoryReservationStatus.RELEASING));
    return mongo.updateFirst(releasing,
            new Update().set("status", InventoryReservationStatus.RELEASED),
            InventoryReservation.class)
        .flatMap(result -> result.getModifiedCount() == 1
            ? Mono.just(true)
            : releasedAlready(reservationId))
        .then();
  }

  private Mono<InventoryReservation> releasingAlready(String reservationId) {
    return mongo.findById(reservationId, InventoryReservation.class)
        .flatMap(existing -> existing.getStatus()
            == InventoryReservationStatus.RELEASING
                ? Mono.just(existing)
                : existing.getStatus() == InventoryReservationStatus.RELEASED
                    ? Mono.empty()
                    : Mono.error(conflict(
                        "Reservation cannot be released in its current state.")));
  }

  private Mono<Boolean> releasedAlready(String reservationId) {
    return mongo.findById(reservationId, InventoryReservation.class)
        .switchIfEmpty(Mono.error(conflict("Reservation was not found.")))
        .flatMap(existing -> existing.getStatus()
            == InventoryReservationStatus.RELEASED
                ? Mono.just(true)
                : Mono.error(conflict(
                    "Reservation could not be marked as released.")));
  }

  private Mono<Boolean> acceptedAlready(String reservationId, String orderId) {
    return mongo.findById(reservationId, InventoryReservation.class)
        .switchIfEmpty(Mono.error(conflict("Reservation was not found.")))
        .flatMap(existing -> existing.getStatus()
                == InventoryReservationStatus.ACCEPTED
            && orderId.equals(existing.getOrderId())
                ? Mono.just(true)
                : Mono.error(conflict(
                    "Reservation cannot be accepted in its current state.")));
  }

  private List<InventoryReservationItem> requestedItems(TacoOrder order) {
    Map<String, Long> quantities = new TreeMap<>();
    for (OrderLine line : safe(order.getItems())) {
      if (line == null || line.getTaco() == null || line.getQuantity() < 1) {
        throw invalid("Each order line must have a positive quantity.");
      }
      Taco taco = line.getTaco();
      for (Ingredient ingredient : safe(taco.getIngredients())) {
        if (ingredient == null || !StringUtils.hasText(ingredient.getId())) {
          throw invalid("Every reserved ingredient must have an ID.");
        }
        quantities.merge(ingredient.getId(), (long) line.getQuantity(),
            Math::addExact);
      }
    }

    List<InventoryReservationItem> items = new ArrayList<>();
    quantities.forEach((ingredientId, quantity) -> {
      if (quantity > Integer.MAX_VALUE) {
        throw invalid("Requested ingredient quantity is too large.");
      }
      items.add(new InventoryReservationItem(
          ingredientId, quantity.intValue()));
    });
    return items;
  }

  private String reservationId(TacoOrder order) {
    synchronized (order) {
      if (!StringUtils.hasText(order.getInventoryReservationId())) {
        order.setInventoryReservationId(UUID.randomUUID().toString());
      }
      return order.getInventoryReservationId();
    }
  }

  private InventoryReservationException invalid(String message) {
    return new InventoryReservationException(
        "INVENTORY_RESERVATION_INVALID", message);
  }

  private InventoryReservationException conflict(String message) {
    return new InventoryReservationException(
        "INVENTORY_RESERVATION_CONFLICT", message);
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? Collections.emptyList() : values;
  }
}
