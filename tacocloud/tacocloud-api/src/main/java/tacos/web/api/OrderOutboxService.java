package tacos.web.api;

import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.messaging.OrderEvent;
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxRepository;

@Service
public class OrderOutboxService {
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
    return Mono.defer(() -> orders.save(order))
        .flatMap(saved -> inventory.accept(reservation.getId(), saved.getId())
            .then(Mono.defer(() -> {
              OrderEvent event = OrderEventFactory.created(saved);
              return outbox.save(new OutboxEvent(event.getEventId(),
                  serialize(event), event.getVersion(), Instant.now()))
                  .thenReturn(saved);
            })))
        .as(transaction::transactional);
  }

  private String serialize(OrderEvent event) {
    try {
      return json.writeValueAsString(event);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot serialize order event", error);
    }
  }
}
