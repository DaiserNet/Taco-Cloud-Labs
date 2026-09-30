package tacos.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OutboxHealthIndicatorTest {
  @Test
  void shouldExposeReactiveBacklogThroughGaugeAndHealthWithoutBlocking() {
    OutboxRepository repository = mock(OutboxRepository.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxHealthIndicator health = new OutboxHealthIndicator(repository,
        registry, 60000);
    assertEquals(Status.DOWN, health.health().getStatus());
    when(repository.countByStatusIn(anyCollection())).thenReturn(Mono.just(3L));

    StepVerifier.create(health.refresh()).verifyComplete();

    assertEquals(3.0, registry.get("tacocloud.outbox.pending").gauge().value());
    assertEquals(Status.UP, health.health().getStatus());
    assertEquals(3L, health.health().getDetails().get("pending"));
  }

  @Test
  void shouldDegradeWithSafeReasonAndRecoverAfterDelivery() {
    OutboxRepository repository = mock(OutboxRepository.class);
    OutboxHealthIndicator health = new OutboxHealthIndicator(repository,
        new SimpleMeterRegistry(), 60000);
    when(repository.countByStatusIn(anyCollection()))
        .thenReturn(Mono.just(2L), Mono.just(0L));
    StepVerifier.create(health.refresh()).verifyComplete();
    health.deliveryFailed("BROKER_DELIVERY_FAILED");
    assertEquals(Status.DOWN, health.health().getStatus());
    assertEquals("BROKER_DELIVERY_FAILED", health.health().getDetails()
        .get("reason"));
    assertFalse(health.health().getDetails().toString()
        .contains("password"));

    StepVerifier.create(health.refresh()).verifyComplete();
    assertEquals(Status.UP, health.health().getStatus());
  }

  @Test
  void shouldDegradeWhenBacklogQueryFails() {
    OutboxRepository repository = mock(OutboxRepository.class);
    OutboxHealthIndicator health = new OutboxHealthIndicator(repository,
        new SimpleMeterRegistry(), 60000);
    when(repository.countByStatusIn(anyCollection()))
        .thenReturn(Mono.just(1L),
            Mono.error(new IllegalStateException("mongodb://secret")));
    StepVerifier.create(health.refresh()).verifyComplete();
    StepVerifier.create(health.refresh()).expectError().verify();
    assertEquals(Status.DOWN, health.health().getStatus());
    assertEquals("BACKLOG_QUERY_FAILED", health.health().getDetails()
        .get("reason"));
    assertFalse(health.health().getDetails().toString().contains("secret"));
  }
}
