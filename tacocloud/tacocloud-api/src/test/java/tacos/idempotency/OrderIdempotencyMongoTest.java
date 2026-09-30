package tacos.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import javax.validation.Validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.SimpleReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.support.ReactiveMongoRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.observability.OrderMetrics;
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxRepository;
import tacos.payment.PaymentMethodService;
import tacos.pricing.CouponService;
import tacos.pricing.OrderPricingService;
import tacos.web.api.EmailOrderService;
import tacos.web.api.OrderOutboxService;
import tacos.web.api.OrderService;

@Testcontainers(disabledWithoutDocker = true)
class OrderIdempotencyMongoTest {
  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:4.4.6");

  private static MongoClient client;
  private static ReactiveMongoTemplate mongo;
  private static OrderRepository orders;
  private static OutboxRepository outbox;
  private static IdempotencyRecordRepository records;
  private static TransactionalOperator transaction;
  private OrderIdempotencyService service;
  private UserRepository users;
  private OrderService orderService;

  @BeforeAll
  static void connect() {
    client = MongoClients.create(MONGO.getReplicaSetUrl());
    SimpleReactiveMongoDatabaseFactory factory =
        new SimpleReactiveMongoDatabaseFactory(client, "tc34_" +
            UUID.randomUUID().toString().replace("-", ""));
    mongo = new ReactiveMongoTemplate(factory);
    ReactiveMongoRepositoryFactory repositories =
        new ReactiveMongoRepositoryFactory(mongo);
    orders = repositories.getRepository(OrderRepository.class);
    outbox = repositories.getRepository(OutboxRepository.class);
    records = repositories.getRepository(IdempotencyRecordRepository.class);
    transaction = TransactionalOperator.create(
        new ReactiveMongoTransactionManager(factory));
    StepVerifier.create(mongo.createCollection(TacoOrder.class)
        .then(mongo.createCollection(Ingredient.class))
        .then(mongo.createCollection(InventoryReservation.class))
        .then(mongo.createCollection(OutboxEvent.class))
        .then(mongo.createCollection(IdempotencyRecord.class))
        .then(mongo.indexOps(IdempotencyRecord.class).ensureIndex(new Index()
            .on("userId", Sort.Direction.ASC)
            .on("key", Sort.Direction.ASC).unique()))
        .then(mongo.indexOps(IdempotencyRecord.class).ensureIndex(new Index()
            .on("expiresAt", Sort.Direction.ASC).expire(0)))
        .then()).verifyComplete();
  }

  @AfterAll
  static void disconnect() {
    if (client != null) {
      client.close();
    }
  }

  @BeforeEach
  void setUp() {
    StepVerifier.create(orders.deleteAll()
        .then(outbox.deleteAll())
        .then(records.deleteAll())
        .then(mongo.remove(new org.springframework.data.mongodb.core.query.Query(),
            InventoryReservation.class))
        .then(mongo.remove(new org.springframework.data.mongodb.core.query.Query(),
            Ingredient.class))
        .then(mongo.insert(ingredient("WRAP", Ingredient.Type.WRAP)))
        .then(mongo.insert(ingredient("SLSA", Ingredient.Type.SAUCE)))
        .then()).verifyComplete();

    users = mock(UserRepository.class);
    when(users.findByUsername("alice")).thenReturn(Mono.just(user("alice")));
    when(users.findByUsername("bob")).thenReturn(Mono.just(user("bob")));
    PaymentMethodService payments = mock(PaymentMethodService.class);
    when(payments.findOwned(eq("PAYMENT-ID"), any(Authentication.class)))
        .thenAnswer(invocation -> {
          Authentication authentication = invocation.getArgument(1);
          return Mono.just(new PaymentMethod(user(authentication.getName()),
              "token", "VISA", "0002", "12/99"));
        });
    OrderPricingService pricing = mock(OrderPricingService.class);
    when(pricing.price(any(TacoOrder.class))).thenAnswer(invocation -> {
      TacoOrder order = invocation.getArgument(0);
      order.getItems().get(0).getTaco().setIngredients(Arrays.asList(
          ingredient("WRAP", Ingredient.Type.WRAP),
          ingredient("SLSA", Ingredient.Type.SAUCE)));
      return Mono.just(order);
    });
    CouponService coupons = mock(CouponService.class);
    when(coupons.apply(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    InventoryService inventory = new InventoryService(mongo);
    OrderOutboxService save = new OrderOutboxService(orders, inventory, outbox,
        new ObjectMapper(), transaction);
    orderService = new OrderService(orders,
        mock(EmailOrderService.class), save, users, mock(Validator.class),
        payments, pricing, coupons, inventory,
        new OrderMetrics(new SimpleMeterRegistry(), "noop"));
    OrderMapper mapper = new OrderMapper();
    service = new OrderIdempotencyService(records, users, orders, mongo,
        orderService, mapper, new OrderRequestFingerprint(),
        Clock.systemUTC(), 24, 3000);
  }

  @Test
  void sequentialRetryKeepsOneOrderReservationAndOutboxEvent() {
    StepVerifier.create(service.create(request(), "same-request-key", auth("alice"))
        .flatMap(first -> service.create(request(), "same-request-key", auth("alice"))
            .map(second -> reactor.util.function.Tuples.of(first, second))))
        .assertNext(pair -> assertEquals(pair.getT1(), pair.getT2()))
        .verifyComplete();
    assertSingleEffect();
  }

  @Test
  void concurrentRetryKeepsOneOrderReservationAndOutboxEvent() {
    StepVerifier.create(Mono.zip(
        service.create(request(), "parallel-key-123", auth("alice")),
        service.create(request(), "parallel-key-123", auth("alice"))))
        .assertNext(pair -> assertEquals(pair.getT1(), pair.getT2()))
        .verifyComplete();
    assertSingleEffect();
  }

  @Test
  void changedPayloadReturnsConflictWithoutSecondEffects() {
    StepVerifier.create(service.create(request(), "body-conflict-key", auth("alice")))
        .expectNextCount(1).verifyComplete();
    OrderCreateRequest changed = request();
    changed.getItems().get(0).setQuantity(2);
    StepVerifier.create(service.create(changed, "body-conflict-key", auth("alice")))
        .expectErrorMatches(error -> error instanceof ResponseStatusException
            && ((ResponseStatusException) error).getStatus() == HttpStatus.CONFLICT)
        .verify();
    assertSingleEffect();
  }

  @Test
  void sameKeyForTwoUsersCreatesTwoIndependentOrders() {
    StepVerifier.create(Mono.zip(
        service.create(request(), "per-user-key", auth("alice")),
        service.create(request(), "per-user-key", auth("bob"))))
        .assertNext(pair -> assertNotEquals(pair.getT1().getId(), pair.getT2().getId()))
        .verifyComplete();
    StepVerifier.create(orders.count().zipWith(outbox.count())
        .zipWith(records.count()))
        .assertNext(counts -> {
          assertEquals(2L, counts.getT1().getT1().longValue());
          assertEquals(2L, counts.getT1().getT2().longValue());
          assertEquals(2L, counts.getT2().longValue());
        }).verifyComplete();
  }

  @Test
  void failedRecordCompletionRollsBackOrderAndOutboxAndRestoresStock() {
    IdempotencyRecordRepository failing = mock(IdempotencyRecordRepository.class);
    when(failing.save(any(IdempotencyRecord.class)))
        .thenReturn(Mono.error(new IllegalStateException("record write failed")));
    when(failing.deleteById(any(String.class))).thenAnswer(invocation ->
        records.deleteById((String) invocation.getArgument(0)));
    service = new OrderIdempotencyService(failing, users, orders,
        mongo, orderService, new OrderMapper(),
        new OrderRequestFingerprint(), Clock.systemUTC(), 24, 3000);

    StepVerifier.create(service.create(request(), "failed-record-key", auth("alice")))
        .expectErrorMessage("record write failed").verify();
    StepVerifier.create(orders.count().zipWith(outbox.count())
        .zipWith(records.count())
        .zipWith(mongo.findById("WRAP", Ingredient.class)))
        .assertNext(result -> {
          assertEquals(0L, result.getT1().getT1().getT1().longValue());
          assertEquals(0L, result.getT1().getT1().getT2().longValue());
          assertEquals(0L, result.getT1().getT2().longValue());
          assertEquals(10, result.getT2().getStockOnHand());
        }).verifyComplete();
  }

  private void assertSingleEffect() {
    StepVerifier.create(orders.count().zipWith(outbox.count())
        .zipWith(records.findAll().single())
        .zipWith(mongo.findById("WRAP", Ingredient.class)))
        .assertNext(result -> {
          assertEquals(1L, result.getT1().getT1().getT1().longValue());
          assertEquals(1L, result.getT1().getT1().getT2().longValue());
          IdempotencyRecord record = result.getT1().getT2();
          assertEquals(IdempotencyRecord.Status.COMPLETED, record.getStatus());
          assertNotNull(record.getResponse());
          assertNotNull(record.getExpiresAt());
          assertEquals(9, result.getT2().getStockOnHand());
        }).verifyComplete();
    StepVerifier.create(mongo.findById("SLSA", Ingredient.class)
        .zipWith(mongo.count(new org.springframework.data.mongodb.core.query.Query(),
            InventoryReservation.class)))
        .assertNext(pair -> {
          assertEquals(9, pair.getT1().getStockOnHand());
          assertEquals(1L, pair.getT2().longValue());
        }).verifyComplete();
  }

  private Ingredient ingredient(String id, Ingredient.Type type) {
    return new Ingredient(id, id, type, new BigDecimal("1.00"), true, 10, 2);
  }

  private User user(String username) {
    User user = new User(username, "password", username, "Street", "City",
        "ST", "00000", "0000000000", username + "@example.test");
    user.setId(username + "-id");
    return user;
  }

  private Authentication auth(String username) {
    return new UsernamePasswordAuthenticationToken(username, "password",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
  }

  private OrderCreateRequest request() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Alice");
    request.setDeliveryStreet("Street");
    request.setDeliveryCity("City");
    request.setDeliveryState("ST");
    request.setDeliveryZip("00000");
    request.setPaymentMethodId("PAYMENT-ID");
    OrderCreateRequest.TacoItem taco = new OrderCreateRequest.TacoItem();
    taco.setName("Valid taco");
    taco.setIngredientIds(Arrays.asList("WRAP", "SLSA"));
    OrderCreateRequest.OrderItem item = new OrderCreateRequest.OrderItem();
    item.setTaco(taco);
    item.setQuantity(1);
    request.setItems(Collections.singletonList(item));
    return request;
  }
}
