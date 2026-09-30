package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.correlation.CorrelationContext;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.messaging.OrderEvent;
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxRepository;

class OrderOutboxCorrelationTest {
  @Test
  void shouldUseReactorContextInEventOutboxAndAsyncLog() throws Exception {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-1");
    OrderRepository orders = mock(OrderRepository.class);
    InventoryService inventory = mock(InventoryService.class);
    OutboxRepository outbox = mock(OutboxRepository.class);
    TransactionalOperator transaction = mock(TransactionalOperator.class);
    InventoryReservation reservation = mock(InventoryReservation.class);
    when(reservation.getId()).thenReturn("RES-1");
    when(orders.save(order)).thenReturn(Mono.just(order));
    when(inventory.accept("RES-1", "ORDER-1")).thenReturn(Mono.empty());
    when(outbox.save(any(OutboxEvent.class))).thenAnswer(call ->
        Mono.just(call.getArgument(0)));
    when(transaction.transactional(any(Mono.class))).thenAnswer(call ->
        call.getArgument(0));
    Logger logger = (Logger) LoggerFactory.getLogger(OrderOutboxService.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      OrderOutboxService service = new OrderOutboxService(orders, inventory,
          outbox, new ObjectMapper(), transaction);
      StepVerifier.create(service.saveAcceptedOrder(order, reservation)
          .doOnNext(saved -> assertNull(MDC.get(CorrelationContext.MDC_KEY)))
          .subscribeOn(Schedulers.parallel())
          .contextWrite(context -> context.put(CorrelationContext.CONTEXT_KEY,
              "client-123")))
          .expectNext(order).verifyComplete();

      ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
      org.mockito.Mockito.verify(outbox).save(saved.capture());
      OrderEvent event = new ObjectMapper().readValue(
          saved.getValue().getPayloadJson(), OrderEvent.class);
      assertEquals("client-123", event.getCorrelationId());
      assertNotEquals(order.getId(), event.getCorrelationId());
      assertEquals("client-123", logs.list.get(0).getMDCPropertyMap()
          .get(CorrelationContext.MDC_KEY));
      assertEquals(event.getEventId(), saved.getValue().getEventId());
      assertNull(MDC.get(CorrelationContext.MDC_KEY));
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }
}
