package tacos.kitchen.delivery;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.test.StepVerifier;
import tacos.kitchen.TacoKitchenApplication;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;
import tacos.messaging.RabbitOrderMessagingService;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("rabbitmq-listener")
@SpringBootTest(classes = TacoKitchenApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "tacocloud.messaging.rabbitmq.destination=tc30.orders",
        "tacocloud.kitchen.consumer.max-retries=2",
        "tacocloud.kitchen.consumer.backoff-ms=100",
        "tacocloud.kitchen.consumer.max-backoff-ms=200",
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1"
    })
class RabbitKitchenDeliveryTest {
  private static final String QUEUE = "tc30.orders";
  private static final String DLQ = QUEUE + ".dlq";

  @Container static final MongoDBContainer MONGO =
      new MongoDBContainer("mongo:4.4.6");
  @Container static final RabbitMQContainer RABBIT =
      new RabbitMQContainer("rabbitmq:3.13-alpine");

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
    registry.add("spring.rabbitmq.addresses", RABBIT::getAmqpUrl);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
  }

  @Autowired private MongoTemplate mongo;
  @Autowired private TransactionTemplate transaction;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private MeterRegistry metrics;
  @Autowired private ObjectMapper json;
  @SpyBean private KitchenEventProcessor processor;

  @BeforeEach
  void clear() {
    assertTrue(mongo.collectionExists(KitchenOrderReceipt.class));
    assertTrue(mongo.collectionExists(ProcessedOrderEvent.class));
    mongo.remove(new Query(), KitchenOrderReceipt.class);
    mongo.remove(new Query(), ProcessedOrderEvent.class);
    await().atMost(Duration.ofSeconds(10))
        .until(() -> admin.getQueueProperties(QUEUE) != null);
    admin.purgeQueue(QUEUE, false);
    admin.purgeQueue(QUEUE + ".retry", false);
    admin.purgeQueue(DLQ, false);
  }

  @Test
  void shouldConsumePublishedEventTwiceButChangeKitchenStateOnce() {
    OrderEvent event = event("ORDER-1");
    RabbitOrderMessagingService publisher =
        new RabbitOrderMessagingService(rabbit, QUEUE, 5000);
    double duplicates = metric("duplicate");
    long processing = metrics.timer("tacocloud.kitchen.processing",
        "result", "processed", "transport", "rabbitmq").count();

    StepVerifier.create(publisher.publish(event)).verifyComplete();
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      assertEquals(1, mongo.count(new Query(), KitchenOrderReceipt.class));
      assertEquals(1, mongo.count(new Query(), ProcessedOrderEvent.class));
    });
    StepVerifier.create(publisher.publish(event)).verifyComplete();
    await().atMost(Duration.ofSeconds(10))
        .until(() -> metric("duplicate") > duplicates);

    assertEquals(1, mongo.count(new Query(), KitchenOrderReceipt.class));
    assertEquals(processing + 1, metrics.timer("tacocloud.kitchen.processing",
        "result", "processed", "transport", "rabbitmq").count());
    ProcessedOrderEvent marker = mongo.findById(event.getEventId(),
        ProcessedOrderEvent.class);
    assertNotNull(marker);
    assertEquals("RECEIVED", marker.getResult());
    assertNotNull(marker.getProcessedAt());
    assertEquals("RECEIVED", mongo.findById(event.getPayload().getOrderId(),
        KitchenOrderReceipt.class).getStatus());
  }

  @Test
  void shouldUseEventIdRatherThanOrderIdForDeduplication() {
    OrderEvent first = event("ORDER-SAME");
    OrderEvent second = event("ORDER-SAME");

    assertEquals(KitchenEventProcessor.Result.PROCESSED,
        processor.process(first));
    assertEquals(KitchenEventProcessor.Result.PROCESSED,
        processor.process(second));

    assertEquals(1, mongo.count(new Query(), KitchenOrderReceipt.class));
    assertEquals(2, mongo.count(new Query(), ProcessedOrderEvent.class));
    assertEquals("ALREADY_RECEIVED", mongo.findById(second.getEventId(),
        ProcessedOrderEvent.class).getResult());
  }

  @Test
  void shouldRetryTransientErrorTwiceWithBackoffAndThenSucceed() {
    OrderEvent event = event("ORDER-RETRY");
    AtomicInteger attempts = new AtomicInteger();
    List<Long> times = Collections.synchronizedList(new ArrayList<>());
    doAnswer(call -> {
      OrderEvent received = call.getArgument(0);
      if (event.getEventId().equals(received.getEventId())) {
        times.add(System.nanoTime());
        if (attempts.incrementAndGet() <= 2) {
          throw new TransientDataAccessResourceException("temporary");
        }
      }
      return call.callRealMethod();
    }).when(processor).process(any(OrderEvent.class));

    rabbit.convertAndSend(QUEUE, event);
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
      assertEquals(3, attempts.get());
      assertNotNull(mongo.findById(event.getEventId(),
          ProcessedOrderEvent.class));
    });
    assertTrue(Duration.ofNanos(times.get(1) - times.get(0)).toMillis() >= 70);
    assertTrue(Duration.ofNanos(times.get(2) - times.get(1)).toMillis() >= 140);
    assertNull(rabbit.receive(DLQ));
  }

  @Test
  void shouldDeadLetterExhaustedTransientFailure() {
    OrderEvent event = event("ORDER-FAILED");
    double deadLetters = metric("dlq");
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(call -> {
      OrderEvent received = call.getArgument(0);
      if (event.getEventId().equals(received.getEventId())) {
        attempts.incrementAndGet();
        throw new TransientDataAccessResourceException("private detail");
      }
      return call.callRealMethod();
    }).when(processor).process(any(OrderEvent.class));

    rabbit.convertAndSend(QUEUE, event);
    Message letter = receiveEventually(DLQ);
    assertEquals(3, attempts.get());
    assertEquals(2, letter.getMessageProperties().getHeaders()
        .get("x-tacocloud-retry-count"));
    assertEquals("TRANSIENT_EXHAUSTED", letter.getMessageProperties()
        .getHeaders().get("x-tacocloud-cause"));
    assertEquals(event.getEventId(), letter.getMessageProperties()
        .getHeaders().get("x-tacocloud-event-id"));
    assertEquals(event.getCorrelationId(), letter.getMessageProperties()
        .getCorrelationId());
    assertTrue(!letter.getMessageProperties().getHeaders().toString()
        .contains("private detail"));
    assertEquals(0, mongo.count(new Query(), KitchenOrderReceipt.class));
    assertEquals(deadLetters + 1, metric("dlq"));
  }

  @Test
  void shouldDeadLetterUnsupportedVersionWithoutRetry() {
    OrderEvent current = event("ORDER-VERSION");
    OrderEvent unsupported = new OrderEvent(current.getEventId(),
        OrderEventType.ORDER_CREATED, 2, Instant.now().toString(),
        current.getCorrelationId(), current.getPayload());

    rabbit.convertAndSend(QUEUE, unsupported);
    Message letter = receiveEventually(DLQ);
    assertEquals("UNSUPPORTED_VERSION", letter.getMessageProperties()
        .getHeaders().get("x-tacocloud-cause"));
    assertEquals(0, letter.getMessageProperties().getHeaders()
        .get("x-tacocloud-retry-count"));
    assertEquals(0, mongo.count(new Query(), ProcessedOrderEvent.class));
  }

  @Test
  void shouldDeadLetterUnknownEventTypeWithoutCopyingUnknownPayload() throws Exception {
    OrderEvent event = event("ORDER-UNKNOWN");
    String invalid = json.writeValueAsString(event)
        .replace("ORDER_CREATED", "FUTURE_EVENT");
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);

    rabbit.send(QUEUE, new Message(invalid.getBytes(StandardCharsets.UTF_8),
        properties));
    Message letter = receiveEventually(DLQ);

    assertEquals("INVALID_EVENT", letter.getMessageProperties()
        .getHeaders().get("x-tacocloud-cause"));
    assertEquals("{}", new String(letter.getBody(), StandardCharsets.UTF_8));
    assertEquals(0, mongo.count(new Query(), ProcessedOrderEvent.class));
  }

  @Test
  void shouldSurviveCrashBetweenDurableEffectAndAck() {
    OrderEvent event = event("ORDER-CRASH");
    assertEquals(KitchenEventProcessor.Result.PROCESSED,
        processor.process(event));
    double duplicates = metric("duplicate");

    // The first delivery committed, but its acknowledgement was lost.
    rabbit.convertAndSend(QUEUE, event);
    await().atMost(Duration.ofSeconds(10))
        .until(() -> metric("duplicate") > duplicates);

    assertEquals(1, mongo.count(new Query(), KitchenOrderReceipt.class));
    assertEquals(1, mongo.count(new Query(), ProcessedOrderEvent.class));
  }

  @Test
  void shouldReplayDeadLetterWithoutRepeatingBusinessEffect() {
    OrderEvent event = event("ORDER-REPLAY");
    assertEquals(KitchenEventProcessor.Result.PROCESSED,
        processor.process(event));
    rabbit.convertAndSend(DLQ, event);
    Message letter = receiveEventually(DLQ);
    double duplicates = metric("duplicate");

    rabbit.send(QUEUE, letter);
    await().atMost(Duration.ofSeconds(10))
        .until(() -> metric("duplicate") > duplicates);

    assertEquals(1, mongo.count(new Query(), KitchenOrderReceipt.class));
    assertEquals(1, mongo.count(new Query(), ProcessedOrderEvent.class));
  }

  @Test
  void shouldRollbackMarkerWhenReceiptWriteFails() {
    OrderEvent event = event("ORDER-ROLLBACK");
    MongoTemplate failing = spy(mongo);
    doThrow(new IllegalStateException("receipt write failed"))
        .when(failing).insert(any(KitchenOrderReceipt.class));
    KitchenEventProcessor withFailure =
        new KitchenEventProcessor(failing, transaction);

    assertThrows(IllegalStateException.class, () -> withFailure.process(event));
    assertNull(mongo.findById(event.getEventId(), ProcessedOrderEvent.class));
    assertNull(mongo.findById(event.getPayload().getOrderId(),
        KitchenOrderReceipt.class));
  }

  private OrderEvent event(String orderId) {
    return OrderEvent.created("correlation-" + UUID.randomUUID(),
        new OrderEventPayload(orderId, Collections.emptyList()));
  }

  private Message receiveEventually(String queue) {
    AtomicReference<Message> received = new AtomicReference<>();
    await().atMost(Duration.ofSeconds(15)).until(() -> {
      Message message = rabbit.receive(queue);
      if (message != null) {
        received.set(message);
      }
      return received.get() != null;
    });
    return received.get();
  }

  private double metric(String outcome) {
    return metrics.counter("tacocloud.kitchen.event.delivery",
        "outcome", outcome).count();
  }
}
