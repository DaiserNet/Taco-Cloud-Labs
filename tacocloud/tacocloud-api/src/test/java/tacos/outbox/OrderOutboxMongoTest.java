package tacos.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.SimpleReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.mongodb.repository.support.ReactiveMongoRepositoryFactory;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.correlation.CorrelationContext;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.OrderOutboxService;

@Testcontainers(disabledWithoutDocker = true)
class OrderOutboxMongoTest {
  @Container static final MongoDBContainer MONGO =
      new MongoDBContainer("mongo:4.4.6");

  private static MongoClient client;
  private static ReactiveMongoTemplate mongo;
  private static OrderRepository orders;
  private static OutboxRepository outbox;
  private static TransactionalOperator transaction;
  private static final ObjectMapper JSON = new ObjectMapper();

  @BeforeAll
  static void connect() {
    client = MongoClients.create(MONGO.getReplicaSetUrl());
    SimpleReactiveMongoDatabaseFactory factory =
        new SimpleReactiveMongoDatabaseFactory(client, "tc29_" +
            UUID.randomUUID().toString().replace("-", ""));
    mongo = new ReactiveMongoTemplate(factory);
    ReactiveMongoRepositoryFactory repositories =
        new ReactiveMongoRepositoryFactory(mongo);
    orders = repositories.getRepository(OrderRepository.class);
    outbox = repositories.getRepository(OutboxRepository.class);
    transaction = TransactionalOperator.create(
        new ReactiveMongoTransactionManager(factory));
    StepVerifier.create(mongo.createCollection(TacoOrder.class)
        .then(mongo.createCollection(OutboxEvent.class)).then())
        .verifyComplete();
  }

  @AfterAll
  static void disconnect() {
    if (client != null) {
      client.close();
    }
  }

  @BeforeEach
  void clear() {
    StepVerifier.create(orders.deleteAll().then(outbox.deleteAll()))
        .verifyComplete();
  }

  @Test
  void shouldCommitOrderAndNewOutboxTogether() {
    TacoOrder order = order();
    StepVerifier.create(commit(outbox).saveAcceptedOrder(order, reservation())
        .contextWrite(context -> context.put(CorrelationContext.CONTEXT_KEY,
            "client-123")))
        .expectNextCount(1).verifyComplete();

    StepVerifier.create(orders.findById(order.getId())
        .zipWith(outbox.findAll().single()))
        .assertNext(pair -> {
          assertEquals(order.getId(), pair.getT1().getId());
          assertEquals(OutboxEvent.Status.NEW, pair.getT2().getStatus());
          assertEquals(1, pair.getT2().getVersion());
          assertTrue(pair.getT2().getPayloadJson().contains(order.getId()));
          assertTrue(pair.getT2().getPayloadJson()
              .contains("\"correlationId\":\"client-123\""));
          assertTrue(!pair.getT2().getPayloadJson().contains("paymentMethodId"));
        }).verifyComplete();
  }

  @Test
  void shouldMeasureRealPendingOutboxDocumentsWithoutLoadingPayloads() {
    OutboxEvent event = insertEvent();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxHealthIndicator health = new OutboxHealthIndicator(outbox,
        registry, 60000);

    StepVerifier.create(health.refresh()).verifyComplete();
    assertEquals(1.0, registry.get("tacocloud.outbox.pending").gauge().value());

    StepVerifier.create(mongo.updateFirst(
        Query.query(Criteria.where("_id").is(event.getEventId())),
        Update.update("status", OutboxEvent.Status.PUBLISHED),
        OutboxEvent.class)).expectNextCount(1).verifyComplete();
    StepVerifier.create(health.refresh()).verifyComplete();
    assertEquals(0.0, registry.get("tacocloud.outbox.pending").gauge().value());
  }

  @Test
  void shouldRollbackOrderWhenOutboxInsertFailsBeforeCommit() {
    OutboxRepository failing = mock(OutboxRepository.class);
    when(failing.save(any(OutboxEvent.class)))
        .thenReturn(Mono.error(new IllegalStateException("outbox write failed")));
    TacoOrder order = order();

    StepVerifier.create(commit(failing).saveAcceptedOrder(order, reservation()))
        .expectErrorMessage("outbox write failed").verify();

    StepVerifier.create(orders.count().zipWith(outbox.count()))
        .assertNext(counts -> {
          assertEquals(0L, counts.getT1());
          assertEquals(0L, counts.getT2());
        }).verifyComplete();
  }

  @Test
  void shouldRetryBrokerFailureAfterRestartWithSameEventId() {
    OutboxEvent pending = insertEvent();
    OutboxHealthIndicator health = new OutboxHealthIndicator(outbox,
        new SimpleMeterRegistry(), 60000);
    StepVerifier.create(health.refresh()).verifyComplete();
    OrderMessagingService failedBroker = mock(OrderMessagingService.class);
    when(failedBroker.publish(any(OrderEvent.class)))
        .thenReturn(Mono.error(new IllegalStateException("broker down")));

    StepVerifier.create(publisher(failedBroker, 3, health).publishBatch())
        .verifyComplete();
    assertEquals(Status.DOWN, health.health().getStatus());
    assertEquals("BROKER_DELIVERY_FAILED",
        health.health().getDetails().get("reason"));
    StepVerifier.create(outbox.findById(pending.getEventId()))
        .assertNext(event -> {
          assertEquals(OutboxEvent.Status.FAILED, event.getStatus());
          assertEquals(1, event.getAttempts());
          assertNotNull(event.getNextAttemptAt());
        }).verifyComplete();

    StepVerifier.create(mongo.updateFirst(
        Query.query(Criteria.where("_id").is(pending.getEventId())),
        Update.update("nextAttemptAt", Instant.now().minusSeconds(1)),
        OutboxEvent.class)).expectNextCount(1).verifyComplete();
    OrderMessagingService recoveredBroker = mock(OrderMessagingService.class);
    when(recoveredBroker.publish(any(OrderEvent.class))).thenReturn(Mono.empty());

    StepVerifier.create(publisher(recoveredBroker, 3, health).publishBatch())
        .verifyComplete();
    StepVerifier.create(health.refresh()).verifyComplete();
    assertEquals(Status.UP, health.health().getStatus());
    StepVerifier.create(outbox.findById(pending.getEventId()))
        .assertNext(event -> {
          assertEquals(OutboxEvent.Status.PUBLISHED, event.getStatus());
          assertNotNull(event.getPublishedAt());
        }).verifyComplete();
    verify(recoveredBroker).publish(org.mockito.ArgumentMatchers.argThat(
        event -> pending.getEventId().equals(event.getEventId())));
  }

  @Test
  void shouldRecoverExpiredClaimAfterRestart() {
    OutboxEvent pending = insertEvent();
    StepVerifier.create(mongo.updateFirst(
        Query.query(Criteria.where("_id").is(pending.getEventId())),
        new Update().set("status", OutboxEvent.Status.PUBLISHING)
            .set("claimId", "old-process")
            .set("leaseUntil", Instant.now().minusSeconds(1)),
        OutboxEvent.class)).expectNextCount(1).verifyComplete();
    OrderMessagingService broker = mock(OrderMessagingService.class);
    when(broker.publish(any(OrderEvent.class))).thenReturn(Mono.empty());

    StepVerifier.create(publisher(broker, 3).publishBatch()).verifyComplete();
    StepVerifier.create(outbox.findById(pending.getEventId()))
        .assertNext(event -> assertEquals(OutboxEvent.Status.PUBLISHED,
            event.getStatus())).verifyComplete();
    verify(broker).publish(any(OrderEvent.class));
  }

  @Test
  void shouldAllowOnlyOneOfTwoPublishersToClaimAnEvent() {
    OutboxEvent pending = insertEvent();
    OrderMessagingService broker = mock(OrderMessagingService.class);
    when(broker.publish(any(OrderEvent.class)))
        .thenReturn(Mono.delay(Duration.ofMillis(75)).then());

    StepVerifier.create(Mono.when(publisher(broker, 3).publishBatch(),
        publisher(broker, 3).publishBatch())).verifyComplete();

    verify(broker, times(1)).publish(any(OrderEvent.class));
    StepVerifier.create(outbox.findById(pending.getEventId()))
        .assertNext(event -> assertEquals(OutboxEvent.Status.PUBLISHED,
            event.getStatus())).verifyComplete();
  }

  @Test
  void shouldKeepExhaustedFailureVisibleWithoutClaimingItAgain() {
    OutboxEvent pending = insertEvent();
    OrderMessagingService broker = mock(OrderMessagingService.class);
    when(broker.publish(any(OrderEvent.class)))
        .thenReturn(Mono.error(new IllegalStateException("broker down")));

    StepVerifier.create(publisher(broker, 1).publishBatch()).verifyComplete();
    StepVerifier.create(publisher(broker, 1).publishBatch()).verifyComplete();

    verify(broker, times(1)).publish(any(OrderEvent.class));
    StepVerifier.create(outbox.findById(pending.getEventId()))
        .assertNext(event -> {
          assertEquals(OutboxEvent.Status.FAILED, event.getStatus());
          assertEquals(1, event.getAttempts());
          assertTrue(event.getNextAttemptAt() == null);
          assertTrue(event.getLastError().contains("broker down"));
        }).verifyComplete();
  }

  private OrderOutboxService commit(OutboxRepository destination) {
    InventoryService inventory = mock(InventoryService.class);
    when(inventory.accept(any(String.class), any(String.class)))
        .thenReturn(Mono.empty());
    return new OrderOutboxService(orders, inventory, destination, JSON,
        transaction);
  }

  private InventoryReservation reservation() {
    InventoryReservation reservation = mock(InventoryReservation.class);
    when(reservation.getId()).thenReturn("RESERVATION-1");
    return reservation;
  }

  private TacoOrder order() {
    TacoOrder order = new TacoOrder();
    order.setId(UUID.randomUUID().toString());
    return order;
  }

  private OutboxEvent insertEvent() {
    String orderId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.created(orderId,
        new OrderEventPayload(orderId, Collections.emptyList()));
    try {
      OutboxEvent pending = new OutboxEvent(event.getEventId(),
          JSON.writeValueAsString(event), event.getVersion(), Instant.now());
      StepVerifier.create(outbox.save(pending)).expectNextCount(1)
          .verifyComplete();
      return pending;
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new AssertionError(error);
    }
  }

  private OutboxPublisher publisher(OrderMessagingService broker,
      int maxAttempts) {
    return publisher(broker, maxAttempts, new OutboxHealthIndicator(outbox,
        new SimpleMeterRegistry(), 60000));
  }

  private OutboxPublisher publisher(OrderMessagingService broker,
      int maxAttempts, OutboxHealthIndicator health) {
    return new OutboxPublisher(mongo, broker, JSON, health,
        2, maxAttempts,
        1000, 60000, 60000, 30000);
  }
}
