package tacos.outbox;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "tacocloud.outbox.poll-enabled",
    havingValue = "true", matchIfMissing = true)
public class OutboxPolling {
  private static final Logger log = LoggerFactory.getLogger(OutboxPolling.class);
  private final OutboxPublisher publisher;
  private final OutboxHealthIndicator health;
  private final AtomicBoolean running = new AtomicBoolean();

  public OutboxPolling(OutboxPublisher publisher, OutboxHealthIndicator health) {
    this.publisher = publisher;
    this.health = health;
  }

  @Scheduled(initialDelayString = "${tacocloud.outbox.poll-delay-ms:5000}",
      fixedDelayString = "${tacocloud.outbox.poll-delay-ms:5000}")
  public void poll() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    publisher.publishBatch().then(health.refresh())
        .doFinally(signal -> running.set(false))
        .subscribe(ignored -> { }, error ->
            log.error("Outbox polling failed; next poll will retry", error));
  }
}
