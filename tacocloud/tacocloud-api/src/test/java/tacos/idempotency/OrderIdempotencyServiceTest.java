package tacos.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.web.api.OrderService;

class OrderIdempotencyServiceTest {
  private final Map<String, IdempotencyRecord> stored = new ConcurrentHashMap<>();
  private final AtomicInteger creations = new AtomicInteger();
  private final OrderMapper mapper = new OrderMapper();
  private final OrderRequestFingerprint fingerprint = new OrderRequestFingerprint();
  private final IdempotencyRecordRepository records = mock(IdempotencyRecordRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final OrderRepository orders = mock(OrderRepository.class);
  private final ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
  private final OrderService orderService = mock(OrderService.class);
  private OrderIdempotencyService service;
  private long placementDelay;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    stored.clear();
    creations.set(0);
    placementDelay = 0;
    when(users.findByUsername(anyString())).thenAnswer(invocation ->
        Mono.just(user(invocation.getArgument(0))));
    when(mongo.insert(any(IdempotencyRecord.class))).thenAnswer(invocation ->
        Mono.defer(() -> {
          IdempotencyRecord claim = invocation.getArgument(0);
          if (stored.putIfAbsent(identity(claim), claim) != null) {
            return Mono.error(new DuplicateKeyException("duplicate user/key"));
          }
          return Mono.just(claim);
        }));
    when(records.findByUserIdAndKey(anyString(), anyString()))
        .thenAnswer(invocation -> Mono.defer(() -> Mono.justOrEmpty(stored.get(
            invocation.getArgument(0) + ":" + invocation.getArgument(1)))));
    when(records.findById(anyString())).thenAnswer(invocation -> Mono.defer(() ->
        Mono.justOrEmpty(stored.values().stream()
            .filter(record -> record.getId().equals(invocation.getArgument(0)))
            .findFirst())));
    when(records.save(any(IdempotencyRecord.class))).thenAnswer(invocation ->
        Mono.defer(() -> {
          IdempotencyRecord record = invocation.getArgument(0);
          stored.put(identity(record), record);
          return Mono.just(record);
        }));
    when(orderService.createOrder(any(TacoOrder.class), any(Authentication.class),
        any(Function.class), eq("HTTP_API"), any(Function.class)))
        .thenAnswer(invocation -> Mono.defer(() -> {
          creations.incrementAndGet();
          TacoOrder order = invocation.getArgument(0);
          Authentication authentication = invocation.getArgument(1);
          Function<TacoOrder, Mono<Void>> complete = invocation.getArgument(4);
          order.setUser(user(authentication.getName()));
          return Mono.delay(Duration.ofMillis(placementDelay))
              .then(Mono.defer(() -> complete.apply(order)))
              .thenReturn(order);
        }));
    service = new OrderIdempotencyService(records, users, orders, mongo,
        orderService, mapper, fingerprint, Clock.systemUTC(), 24, 1000);
  }

  @Test
  void sequentialRetryReturnsIdenticalResponseWithoutNewCreation() {
    OrderCreateRequest request = request();
    StepVerifier.create(service.create(request, "repeat-key-1", auth("alice"))
        .flatMap(first -> service.create(request, "repeat-key-1", auth("alice"))
            .map(second -> reactor.util.function.Tuples.of(first, second))))
        .assertNext(pair -> {
          assertEquals(pair.getT1(), pair.getT2());
          assertEquals(pair.getT1().getId(), pair.getT2().getId());
        }).verifyComplete();
    assertEquals(1, creations.get());
    assertEquals(IdempotencyRecord.Status.COMPLETED,
        stored.values().iterator().next().getStatus());
    assertTrue(stored.values().iterator().next().getExpiresAt()
        .isAfter(stored.values().iterator().next().getCompletedAt()));
  }

  @Test
  void concurrentRetryWaitsForOriginalCreation() {
    placementDelay = 200;
    Mono<OrderResponse> first = service.create(request(), "concurrent-key", auth("alice"));
    Mono<OrderResponse> second = service.create(request(), "concurrent-key", auth("alice"));
    StepVerifier.create(Mono.zip(first, second))
        .assertNext(pair -> assertEquals(pair.getT1(), pair.getT2()))
        .verifyComplete();
    assertEquals(1, creations.get());
  }

  @Test
  void sameKeyWithDifferentRelevantPayloadIsConflict() {
    StepVerifier.create(service.create(request(), "conflict-key", auth("alice")))
        .expectNextCount(1).verifyComplete();
    OrderCreateRequest changed = request();
    changed.getItems().get(0).setQuantity(2);
    StepVerifier.create(service.create(changed, "conflict-key", auth("alice")))
        .expectErrorMatches(error -> status(error, HttpStatus.CONFLICT)).verify();
    assertEquals(1, creations.get());
  }

  @Test
  void sameKeyBelongsToEachUserIndependently() {
    StepVerifier.create(service.create(request(), "shared-key", auth("alice"))
        .zipWith(service.create(request(), "shared-key", auth("bob"))))
        .assertNext(pair -> assertNotEquals(pair.getT1().getId(), pair.getT2().getId()))
        .verifyComplete();
    assertEquals(2, creations.get());
    assertEquals(2, stored.size());
  }

  @Test
  void unfinishedClaimNeverStartsAnotherCreation() {
    IdempotencyRecord unfinished = new IdempotencyRecord("claim", "alice-id",
        "unfinished-key", fingerprint.hash(request()), "order", "reservation",
        java.time.Instant.now());
    stored.put(identity(unfinished), unfinished);
    service = new OrderIdempotencyService(records, users, orders, mongo,
        orderService, mapper, fingerprint, Clock.systemUTC(), 24, 100);

    StepVerifier.create(service.create(request(), "unfinished-key", auth("alice")))
        .expectErrorMatches(error -> status(error, HttpStatus.CONFLICT)).verify();
    assertEquals(0, creations.get());
  }

  @Test
  void rejectsInvalidKeyBeforeEffects() {
    StepVerifier.create(service.create(request(), "bad", auth("alice")))
        .expectErrorMatches(error -> status(error, HttpStatus.BAD_REQUEST)).verify();
    assertEquals(0, creations.get());
    assertEquals(0, stored.size());
  }

  private boolean status(Throwable error, HttpStatus expected) {
    return error instanceof ResponseStatusException
        && ((ResponseStatusException) error).getStatus() == expected;
  }

  private String identity(IdempotencyRecord record) {
    return record.getUserId() + ":" + record.getKey();
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
