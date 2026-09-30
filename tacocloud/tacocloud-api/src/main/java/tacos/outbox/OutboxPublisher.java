package tacos.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderMessagingService;

@Service
public class OutboxPublisher {
  private final ReactiveMongoTemplate mongo;
  private final OrderMessagingService messages;
  private final ObjectMapper json;
  private final OutboxHealthIndicator health;
  private final int batchSize;
  private final int maxAttempts;
  private final Duration backoff;
  private final Duration maxBackoff;
  private final Duration lease;
  private final Duration sendTimeout;

  public OutboxPublisher(ReactiveMongoTemplate mongo, OrderMessagingService messages,
      ObjectMapper json, OutboxHealthIndicator health,
      @Value("${tacocloud.outbox.batch-size:50}") int batchSize,
      @Value("${tacocloud.outbox.max-attempts:10}") int maxAttempts,
      @Value("${tacocloud.outbox.backoff-ms:1000}") long backoffMs,
      @Value("${tacocloud.outbox.max-backoff-ms:60000}") long maxBackoffMs,
      @Value("${tacocloud.outbox.lease-ms:60000}") long leaseMs,
      @Value("${tacocloud.outbox.send-timeout-ms:30000}") long sendTimeoutMs) {
    if (batchSize < 1 || maxAttempts < 1 || backoffMs < 1
        || maxBackoffMs < backoffMs || leaseMs < 1
        || sendTimeoutMs < 1 || sendTimeoutMs >= leaseMs) {
      throw new IllegalArgumentException("Invalid outbox settings");
    }
    this.mongo = mongo;
    this.messages = messages;
    this.json = json;
    this.health = health;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.backoff = Duration.ofMillis(backoffMs);
    this.maxBackoff = Duration.ofMillis(maxBackoffMs);
    this.lease = Duration.ofMillis(leaseMs);
    this.sendTimeout = Duration.ofMillis(sendTimeoutMs);
  }

  public Mono<Void> publishBatch() {
    return Flux.range(0, batchSize)
        .concatMap(ignored -> claimNext().flatMap(this::publishClaimed))
        .then();
  }

  private Mono<OutboxEvent> claimNext() {
    return Mono.defer(() -> {
      Instant now = Instant.now();
      Criteria due = new Criteria().orOperator(
          Criteria.where("status").in(OutboxEvent.Status.NEW,
              OutboxEvent.Status.FAILED)
              .and("attempts").lt(maxAttempts)
              .and("nextAttemptAt").lte(now),
          Criteria.where("status").is(OutboxEvent.Status.PUBLISHING)
              .and("leaseUntil").lte(now));
      Query query = Query.query(due).with(Sort.by(Sort.Direction.ASC,
          "createdAt", "_id"));
      Update update = new Update()
          .set("status", OutboxEvent.Status.PUBLISHING)
          .set("claimId", UUID.randomUUID().toString())
          .set("leaseUntil", now.plus(lease))
          .set("updatedAt", now);
      return mongo.findAndModify(query, update,
          FindAndModifyOptions.options().returnNew(true), OutboxEvent.class);
    });
  }

  private Mono<Void> publishClaimed(OutboxEvent claimed) {
    return Mono.fromCallable(() -> json.readValue(
            claimed.getPayloadJson(), OrderEvent.class))
        .doOnError(error -> health.deliveryFailed("INVALID_OUTBOX_EVENT"))
        .flatMap(event -> messages.publish(event).timeout(sendTimeout)
            .doOnError(error -> health.deliveryFailed("BROKER_DELIVERY_FAILED"))
            .doOnSuccess(ignored -> health.deliveryRecovered()))
        .then(Mono.just(true))
        .onErrorResume(error -> markFailed(claimed, error).thenReturn(false))
        .flatMap(sent -> sent ? markPublished(claimed) : Mono.empty());
  }

  private Mono<Void> markPublished(OutboxEvent claimed) {
    Instant now = Instant.now();
    return mongo.updateFirst(claimedQuery(claimed), new Update()
        .set("status", OutboxEvent.Status.PUBLISHED)
        .set("publishedAt", now)
        .set("updatedAt", now)
        .unset("claimId")
        .unset("leaseUntil")
        .unset("nextAttemptAt")
        .unset("lastError"), OutboxEvent.class).then();
  }

  private Mono<Void> markFailed(OutboxEvent claimed, Throwable error) {
    int attempts = claimed.getAttempts() + 1;
    long delay = backoff.toMillis();
    for (int i = 1; i < attempts && delay < maxBackoff.toMillis(); i++) {
      delay = Math.min(maxBackoff.toMillis(),
          delay > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : delay * 2);
    }
    Instant now = Instant.now();
    String detail = error.toString();
    Update update = new Update()
        .set("status", OutboxEvent.Status.FAILED)
        .set("attempts", attempts)
        .set("updatedAt", now)
        .set("lastError", detail.substring(0, Math.min(detail.length(), 500)))
        .unset("claimId")
        .unset("leaseUntil");
    if (attempts >= maxAttempts) {
      update.unset("nextAttemptAt");
    } else {
      update.set("nextAttemptAt", now.plusMillis(delay));
    }
    return mongo.updateFirst(claimedQuery(claimed), update,
        OutboxEvent.class).then();
  }

  private Query claimedQuery(OutboxEvent claimed) {
    return Query.query(Criteria.where("_id").is(claimed.getEventId())
        .and("status").is(OutboxEvent.Status.PUBLISHING)
        .and("claimId").is(claimed.getClaimId()));
  }
}
