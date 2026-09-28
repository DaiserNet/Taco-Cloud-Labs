package tacos.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

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
          assertFalse(result.getT2().isAvailable());
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
