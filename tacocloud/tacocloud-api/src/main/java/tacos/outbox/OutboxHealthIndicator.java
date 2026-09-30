package tacos.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** A reactive backlog sample shared by the gauge and synchronous health endpoint. */
@Component("outboxHealthIndicator")
public class OutboxHealthIndicator implements HealthIndicator {
  private static final EnumSet<OutboxEvent.Status> PENDING = EnumSet.of(
      OutboxEvent.Status.NEW, OutboxEvent.Status.FAILED,
      OutboxEvent.Status.PUBLISHING);

  private final OutboxRepository repository;
  private final AtomicLong pending = new AtomicLong();
  private final Duration maxAge;
  private volatile Instant refreshedAt;
  private volatile String problem;

  public OutboxHealthIndicator(OutboxRepository repository,
      MeterRegistry registry,
      @Value("${tacocloud.outbox.health-max-age-ms:60000}") long maxAgeMs) {
    if (maxAgeMs < 1) {
      throw new IllegalArgumentException("Invalid outbox health age");
    }
    this.repository = repository;
    this.maxAge = Duration.ofMillis(maxAgeMs);
    Gauge.builder("tacocloud.outbox.pending", pending, AtomicLong::get)
        .description("Pending order events in the MongoDB outbox")
        .baseUnit("events")
        .strongReference(true)
        .register(registry);
  }

  public Mono<Void> refresh() {
    return repository.countByStatusIn(PENDING)
        .doOnNext(count -> {
          pending.set(count);
          refreshedAt = Instant.now();
          if (count == 0) {
            problem = null;
          }
        })
        .doOnError(error -> problem = "BACKLOG_QUERY_FAILED")
        .then();
  }

  public void deliveryFailed(String code) {
    problem = code;
  }

  public void deliveryRecovered() {
    problem = null;
  }

  @Override
  public Health health() {
    Instant last = refreshedAt;
    if (last == null || Duration.between(last, Instant.now()).compareTo(maxAge) > 0) {
      return Health.down().withDetail("component", "outbox")
          .withDetail("reason", "BACKLOG_SAMPLE_STALE").build();
    }
    String failure = problem;
    if (failure != null) {
      return Health.down().withDetail("component", "outbox")
          .withDetail("reason", failure)
          .withDetail("pending", pending.get()).build();
    }
    return Health.up().withDetail("component", "outbox")
        .withDetail("pending", pending.get()).build();
  }
}
