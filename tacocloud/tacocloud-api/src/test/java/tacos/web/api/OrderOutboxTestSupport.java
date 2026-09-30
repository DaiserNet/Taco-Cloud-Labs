package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.function.Function;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;

/** Keeps older controller tests focused on their own behavior; TC-29 tests exercise the real transaction. */
public final class OrderOutboxTestSupport {
  private OrderOutboxTestSupport() { }

  public static OrderOutboxService commitUsing(OrderRepository orders,
      InventoryService inventory) {
    OrderOutboxService outbox = mock(OrderOutboxService.class);
    lenient().when(outbox.saveAcceptedOrder(any(TacoOrder.class),
        any(InventoryReservation.class)))
        .thenAnswer(call -> Mono.defer(() -> {
          TacoOrder order = call.getArgument(0);
          InventoryReservation reservation = call.getArgument(1);
          return orders.save(order)
              .flatMap(saved -> inventory.accept(reservation.getId(),
                  saved.getId()).thenReturn(saved));
        }));
    lenient().when(outbox.saveAcceptedOrder(any(TacoOrder.class),
        any(InventoryReservation.class), any(Function.class)))
        .thenAnswer(call -> Mono.defer(() -> {
          TacoOrder order = call.getArgument(0);
          InventoryReservation reservation = call.getArgument(1);
          Function<TacoOrder, Mono<Void>> afterOutbox = call.getArgument(2);
          return orders.save(order)
              .flatMap(saved -> inventory.accept(reservation.getId(),
                  saved.getId()).thenReturn(saved))
              .flatMap(saved -> afterOutbox.apply(saved).thenReturn(saved));
        }));
    return outbox;
  }
}
