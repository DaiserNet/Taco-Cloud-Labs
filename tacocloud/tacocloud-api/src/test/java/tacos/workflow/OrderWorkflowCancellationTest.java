package tacos.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;

class OrderWorkflowCancellationTest {
  @Test
  void shouldRetryInventoryReleaseWithoutSecondStatusChange() {
    OrderRepository orders = mock(OrderRepository.class);
    InventoryService inventory = mock(InventoryService.class);
    SimpleMeterRegistry metricsRegistry = new SimpleMeterRegistry();
    OrderWorkflowService workflow = new OrderWorkflowService(orders, inventory,
        mock(ReactiveMongoTemplate.class), new tacos.observability.OrderMetrics(
            metricsRegistry, "noop"));
    User owner = new User("alice", "encoded", "Alice", "Street", "City",
        "ST", "12345", "5551234", "alice@example.com");
    TacoOrder order = new TacoOrder();
    order.setId("O1");
    order.setUser(owner);
    order.setVersion(0L);
    order.setInventoryReservationId("R1");
    when(orders.findById("O1")).thenReturn(Mono.just(order));
    when(orders.save(order)).thenReturn(Mono.just(order));
    when(inventory.release("R1"))
        .thenReturn(Mono.error(new IllegalStateException("release failed")),
            Mono.empty());
    UsernamePasswordAuthenticationToken alice =
        new UsernamePasswordAuthenticationToken("alice", "unused",
            AuthorityUtils.createAuthorityList("ROLE_USER"));

    StepVerifier.create(workflow.cancel("O1", 0L, "Changed my mind", alice))
        .expectErrorMatches(error -> error instanceof IllegalStateException
            && "release failed".equals(error.getMessage()))
        .verify();
    assertEquals(OrderStatus.CANCELLED, order.getStatus());

    StepVerifier.create(workflow.cancel("O1", 0L, "retry", alice))
        .assertNext(saved -> {
          assertEquals(OrderStatus.CANCELLED, saved.getStatus());
          assertEquals(1, saved.getStatusHistory().size());
        }).verifyComplete();
    verify(orders, times(1)).save(order);
    verify(inventory, times(2)).release("R1");
    assertEquals(1, metricsRegistry.get("tacocloud.orders.cancelled")
        .tag("source", "customer").counter().count());
  }
}
