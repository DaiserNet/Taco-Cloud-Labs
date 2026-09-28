package tacos.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.test.annotation.DirtiesContext;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.OrderLine;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.IngredientRepository;

@SpringBootTest(
    classes = InventoryServiceMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.config.name=tc16-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc16-test",
        "spring.mongodb.embedded.version=3.5.5",
        "logging.level.org.springframework.boot.autoconfigure.mongo.embedded=OFF"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryServiceMongoTest {

  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = IngredientRepository.class)
  @Import(InventoryService.class)
  static class TestApplication {
  }

  @Autowired
  private IngredientRepository ingredientRepo;

  @Autowired
  private ReactiveMongoTemplate mongo;

  @Autowired
  private InventoryService inventoryService;

  @BeforeEach
  void cleanCollections() {
    StepVerifier.create(mongo.remove(new Query(), InventoryReservation.class)
            .then(ingredientRepo.deleteAll()))
        .verifyComplete();
  }

  @Test
  void shouldAllowOnlyOneBuyerWhenTwoReservationsCompeteForOneUnit() {
    TacoOrder firstOrder = order("RESERVATION-1", 1, "ONLY");
    TacoOrder secondOrder = order("RESERVATION-2", 1, "ONLY");

    Mono<Boolean> first = inventoryService.reserve(firstOrder)
        .map(reservation -> true)
        .onErrorReturn(InsufficientStockException.class, false);
    Mono<Boolean> second = inventoryService.reserve(secondOrder)
        .map(reservation -> true)
        .onErrorReturn(InsufficientStockException.class, false);

    ingredientRepo.save(ingredient("ONLY", 1))
        .then(Mono.zip(first, second))
        .flatMap(outcomes -> ingredientRepo.findById("ONLY")
            .map(ingredient -> reactor.util.function.Tuples.of(
                outcomes, ingredient)))
        .as(StepVerifier::create)
        .assertNext(result -> {
          assertNotEquals(result.getT1().getT1(), result.getT1().getT2());
          assertEquals(0, result.getT2().getStockOnHand());
          assertTrue(result.getT2().isAvailable());
        })
        .expectComplete()
        .verify(TEST_TIMEOUT);
  }

  @Test
  void shouldCompensateItemsReservedBeforeALaterIngredientFails() {
    TacoOrder order = order("PARTIAL", 1, "ALPHA", "BRAVO");
    Mono<TacoOrder> setup = ingredientRepo.save(ingredient("ALPHA", 1))
        .then(ingredientRepo.save(ingredient("BRAVO", 0)))
        .thenReturn(order);

    StepVerifier.create(setup.flatMap(inventoryService::reserve))
        .expectErrorMatches(error -> error instanceof InsufficientStockException
            && "BRAVO".equals(
                ((InsufficientStockException) error).getIngredientId()))
        .verify(TEST_TIMEOUT);

    StepVerifier.create(Mono.zip(ingredientRepo.findById("ALPHA"),
            mongo.findById("PARTIAL", InventoryReservation.class)))
        .assertNext(result -> {
          assertEquals(1, result.getT1().getStockOnHand());
          assertTrue(result.getT1().isAvailable());
          assertEquals(InventoryReservationStatus.RELEASED,
              result.getT2().getStatus());
          assertEquals(Arrays.asList("ALPHA", "BRAVO"),
              Arrays.asList(
                  result.getT2().getItems().get(0).getIngredientId(),
                  result.getT2().getItems().get(1).getIngredientId()));
        })
        .verifyComplete();
  }

  @Test
  void shouldNotDiscountStockTwiceWhenReservationIsRetried() {
    TacoOrder order = order("RETRY", 2, "SLSA");

    ingredientRepo.save(ingredient("SLSA", 3))
        .then(inventoryService.reserve(order))
        .flatMap(first -> inventoryService.reserve(order))
        .flatMap(reservation -> ingredientRepo.findById("SLSA")
            .map(ingredient -> reactor.util.function.Tuples.of(
                reservation, ingredient)))
        .as(StepVerifier::create)
        .assertNext(result -> {
          assertEquals("RETRY", result.getT1().getId());
          assertEquals(InventoryReservationStatus.RESERVED,
              result.getT1().getStatus());
          assertEquals(1, result.getT2().getStockOnHand());
        })
        .verifyComplete();
  }

  @Test
  void shouldReleaseAcceptedReservationExactlyOnce() {
    TacoOrder order = order("CANCEL", 1, "CHED");

    ingredientRepo.save(ingredient("CHED", 2))
        .then(inventoryService.reserve(order))
        .flatMap(reservation -> inventoryService
            .accept(reservation.getId(), "ORDER-1")
            .then(inventoryService.release(reservation.getId()))
            .then(inventoryService.release(reservation.getId()))
            .then(mongo.findById(
                reservation.getId(), InventoryReservation.class)))
        .flatMap(reservation -> ingredientRepo.findById("CHED")
            .map(ingredient -> reactor.util.function.Tuples.of(
                reservation, ingredient)))
        .as(StepVerifier::create)
        .assertNext(result -> {
          assertEquals(InventoryReservationStatus.RELEASED,
              result.getT1().getStatus());
          assertEquals("ORDER-1", result.getT1().getOrderId());
          assertEquals(2, result.getT2().getStockOnHand());
          assertTrue(result.getT2().isAvailable());
        })
        .verifyComplete();
  }

  @Test
  void shouldRestoreStockWhenRecordingReservationFailsAfterDecrement() {
    TacoOrder order = order("RECORD-FAIL", 1, "RECORD-ITEM");
    ReactiveMongoTemplate failingMongo = spy(mongo);
    AtomicBoolean failOnce = new AtomicBoolean(true);
    doAnswer(invocation -> failOnce.getAndSet(false)
        ? Mono.error(new IllegalStateException("record write failed"))
        : invocation.callRealMethod())
        .when(failingMongo).updateFirst(any(Query.class),
            any(Update.class), eq(InventoryReservation.class));
    InventoryService failingService = new InventoryService(failingMongo);

    StepVerifier.create(ingredientRepo.save(ingredient("RECORD-ITEM", 2))
            .then(failingService.reserve(order)))
        .expectErrorMatches(error -> error instanceof IllegalStateException
            && "record write failed".equals(error.getMessage()))
        .verify(TEST_TIMEOUT);

    StepVerifier.create(Mono.zip(
            ingredientRepo.findById("RECORD-ITEM"),
            mongo.findById("RECORD-FAIL", InventoryReservation.class),
            mongo.count(new Query(), TacoOrder.class)))
        .assertNext(result -> {
          assertEquals(2, result.getT1().getStockOnHand());
          assertFalse(result.getT1().getInventoryReservationIds()
              .contains("RECORD-FAIL"));
          assertEquals(InventoryReservationStatus.RELEASED,
              result.getT2().getStatus());
          assertTrue(result.getT2().getReservedItems().isEmpty());
          assertEquals(0, result.getT3());
        })
        .verifyComplete();
  }

  @Test
  void shouldPreserveAdministrativeUnavailabilityWhenReleasing() {
    TacoOrder order = order("ADMIN-OFF", 1, "OFF-ITEM");
    ingredientRepo.save(ingredient("OFF-ITEM", 2))
        .then(inventoryService.reserve(order))
        .then(mongo.updateFirst(Query.query(
                org.springframework.data.mongodb.core.query.Criteria
                    .where("_id").is("OFF-ITEM")),
            new Update().set("available", false), Ingredient.class))
        .then(inventoryService.release("ADMIN-OFF"))
        .then(ingredientRepo.findById("OFF-ITEM"))
        .as(StepVerifier::create)
        .assertNext(ingredient -> {
          assertEquals(2, ingredient.getStockOnHand());
          assertFalse(ingredient.isAvailable());
        })
        .verifyComplete();
  }

  @Test
  void shouldAcceptSameReservationTwiceWithoutChangingStock() {
    TacoOrder order = order("ACCEPT-TWICE", 1, "ACCEPT-ITEM");
    ingredientRepo.save(ingredient("ACCEPT-ITEM", 2))
        .then(inventoryService.reserve(order))
        .then(inventoryService.accept("ACCEPT-TWICE", "ORDER-ACCEPT"))
        .then(inventoryService.accept("ACCEPT-TWICE", "ORDER-ACCEPT"))
        .then(Mono.zip(ingredientRepo.findById("ACCEPT-ITEM"),
            mongo.findById("ACCEPT-TWICE", InventoryReservation.class)))
        .as(StepVerifier::create)
        .assertNext(result -> {
          assertEquals(1, result.getT1().getStockOnHand());
          assertEquals(InventoryReservationStatus.ACCEPTED,
              result.getT2().getStatus());
          assertEquals("ORDER-ACCEPT", result.getT2().getOrderId());
        })
        .verifyComplete();
  }

  @Test
  void shouldRetryPartiallyFailedReleaseWithoutRestoringTwice() {
    TacoOrder order = order("RELEASE-RETRY", 1, "FIRST", "SECOND");
    ReactiveMongoTemplate failingMongo = spy(mongo);
    AtomicInteger releaseWrites = new AtomicInteger();
    doAnswer(invocation -> releaseWrites.incrementAndGet() == 2
        ? Mono.error(new IllegalStateException("second release failed"))
        : invocation.callRealMethod())
        .when(failingMongo).updateFirst(any(Query.class),
            any(Update.class), eq(Ingredient.class));
    InventoryService failingService = new InventoryService(failingMongo);

    Mono<Void> setup = ingredientRepo.save(ingredient("FIRST", 2))
        .then(ingredientRepo.save(ingredient("SECOND", 2)))
        .then(inventoryService.reserve(order))
        .then();
    StepVerifier.create(setup.then(failingService.release("RELEASE-RETRY")))
        .expectErrorMatches(error -> error instanceof IllegalStateException
            && "second release failed".equals(error.getMessage()))
        .verify(TEST_TIMEOUT);

    StepVerifier.create(Mono.zip(ingredientRepo.findById("FIRST"),
            ingredientRepo.findById("SECOND"),
            mongo.findById("RELEASE-RETRY", InventoryReservation.class)))
        .assertNext(result -> {
          assertEquals(2, result.getT1().getStockOnHand());
          assertEquals(1, result.getT2().getStockOnHand());
          assertEquals(InventoryReservationStatus.RELEASING,
              result.getT3().getStatus());
        })
        .verifyComplete();

    StepVerifier.create(inventoryService.release("RELEASE-RETRY")
            .then(inventoryService.release("RELEASE-RETRY"))
            .then(Mono.zip(ingredientRepo.findById("FIRST"),
                ingredientRepo.findById("SECOND"),
                mongo.findById("RELEASE-RETRY", InventoryReservation.class))))
        .assertNext(result -> {
          assertEquals(2, result.getT1().getStockOnHand());
          assertEquals(2, result.getT2().getStockOnHand());
          assertTrue(result.getT1().getInventoryReservationIds().isEmpty());
          assertTrue(result.getT2().getInventoryReservationIds().isEmpty());
          assertEquals(InventoryReservationStatus.RELEASED,
              result.getT3().getStatus());
        })
        .verifyComplete();
  }

  private TacoOrder order(
      String reservationId, int quantity, String... ingredientIds) {
    Taco taco = new Taco();
    taco.setName("Inventory taco");
    taco.setIngredients(Arrays.stream(ingredientIds)
        .map(id -> ingredient(id, 1))
        .collect(java.util.stream.Collectors.toList()));
    OrderLine line = new OrderLine();
    line.setTaco(taco);
    line.setQuantity(quantity);
    TacoOrder order = new TacoOrder();
    order.setInventoryReservationId(reservationId);
    order.setItems(Collections.singletonList(line));
    return order;
  }

  private Ingredient ingredient(String id, int stock) {
    return new Ingredient(id, id + " ingredient", Ingredient.Type.WRAP,
        new BigDecimal("0.50"), stock > 0, stock, 1);
  }
}
