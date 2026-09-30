package tacos.web.api;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.correlation.CorrelationContext;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.messaging.OrderEvent;
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxRepository;

@Service
public class OrderOutboxService {
  private static final Logger log = LoggerFactory.getLogger(OrderOutboxService.class);
  private final OrderRepository orders;
  private final InventoryService inventory;
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final TransactionalOperator transaction;

  public OrderOutboxService(OrderRepository orders, InventoryService inventory,
      OutboxRepository outbox, ObjectMapper json, TransactionalOperator transaction) {
    this.orders = orders;
    this.inventory = inventory;
    this.outbox = outbox;
    this.json = json;
    this.transaction = transaction;
  }

  public Mono<TacoOrder> saveAcceptedOrder(
      TacoOrder order, InventoryReservation reservation) {
    return saveAcceptedOrder(order, reservation, saved -> Mono.empty());
  }

  public Mono<TacoOrder> saveAcceptedOrder(
      TacoOrder order, InventoryReservation reservation,
      Function<TacoOrder, Mono<Void>> afterOutbox) {
    return Mono.defer(() -> orders.save(order))
        .flatMap(saved -> inventory.accept(reservation.getId(), saved.getId())
            .then(Mono.deferContextual(context -> {
              String correlationId = context.getOrDefault(
                  CorrelationContext.CONTEXT_KEY, UUID.randomUUID().toString());
              OrderEvent event = OrderEventFactory.created(saved, correlationId);
              return outbox.save(new OutboxEvent(event.getEventId(),
                  serialize(event), event.getVersion(), Instant.now()))
                  .doOnSuccess(ignored -> logStaged(event))
                  .thenReturn(saved);
            })))
        .flatMap(saved -> Mono.defer(() -> afterOutbox.apply(saved))
            .thenReturn(saved))
        .as(transaction::transactional);
  }

  private void logStaged(OrderEvent event) {
    String previous = MDC.get(CorrelationContext.MDC_KEY);
    MDC.put(CorrelationContext.MDC_KEY, event.getCorrelationId());
    try {
      log.info("Order event staged: eventId={}", event.getEventId());
    } finally {
      if (previous == null) {
        MDC.remove(CorrelationContext.MDC_KEY);
      } else {
        MDC.put(CorrelationContext.MDC_KEY, previous);
      }
    }
  }

  private String serialize(OrderEvent event) {
    try {
      return json.writeValueAsString(event);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot serialize order event", error);
    }
  }
}
