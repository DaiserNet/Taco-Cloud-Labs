package tacos.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.inventory.InsufficientStockException;

class OrderMetricsTest {
  @Test
  void shouldCountSuccessfulPlacementCouponAndTimerOnlyAfterSubscription() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OrderMetrics metrics = new OrderMetrics(registry, "rabbitmq");
    TacoOrder order = new TacoOrder();
    order.setCouponApplied(true);
    Mono<TacoOrder> placement = metrics.placement(Mono.just(order), "REORDER");
    assertEquals(0, registry.getMeters().size());

    StepVerifier.create(placement).expectNext(order).verifyComplete();

    assertEquals(1, registry.get("tacocloud.orders.created")
        .tag("source", "REORDER").tag("transport", "rabbitmq")
        .counter().count());
    assertEquals(1, registry.get("tacocloud.coupons.applied")
        .tag("source", "REORDER").counter().count());
    assertEquals(1, registry.get("tacocloud.orders.placement")
        .tag("result", "created").timer().count());
  }

  @Test
  void shouldCountStockRejectionOnceAndUseOnlyBoundedTags() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OrderMetrics metrics = new OrderMetrics(registry, "unexpected-value");

    StepVerifier.create(metrics.placement(Mono.error(
        new InsufficientStockException("SECRET-INGREDIENT", 2)),
        "untrusted-source"))
        .expectError(InsufficientStockException.class).verify();

    assertEquals(1, registry.get("tacocloud.orders.failed")
        .tag("source", "HTTP_API").tag("transport", "noop")
        .counter().count());
    assertEquals(1, registry.get("tacocloud.inventory.stock.rejected")
        .tag("source", "HTTP_API").counter().count());
    assertEquals(1, registry.get("tacocloud.orders.placement")
        .tag("result", "failed").timer().count());
    for (Meter meter : registry.getMeters()) {
      assertFalse(meter.getId().getName().contains("SECRET"));
      meter.getId().getTags().forEach(tag -> {
        assertTrue(Set.of("source", "transport", "result")
            .contains(tag.getKey()));
        assertFalse(tag.getValue().contains("SECRET"));
      });
    }
  }
}
