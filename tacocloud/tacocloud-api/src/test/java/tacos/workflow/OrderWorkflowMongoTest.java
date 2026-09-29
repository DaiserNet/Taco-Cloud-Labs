package tacos.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.OrderLine;
import tacos.OrderStatus;
import tacos.OrderStatusChange;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;

@SpringBootTest(classes = OrderWorkflowMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.config.name=tc25-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc25-test",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.mongodb.embedded.version=3.5.5"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderWorkflowMongoTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = OrderRepository.class)
  @Import({OrderWorkflowService.class, InventoryService.class})
  static class TestApplication {
  }

  @Autowired private OrderWorkflowService workflow;
  @Autowired private OrderRepository orders;
  @Autowired private UserRepository users;
  @Autowired private IngredientRepository ingredients;
  @Autowired private InventoryService inventory;
  @Autowired private ReactiveMongoTemplate mongo;

  private User alice;

  @BeforeEach
  void setUp() {
    StepVerifier.create(orders.deleteAll().then(ingredients.deleteAll())
        .then(users.deleteAll())
        .then(mongo.remove(new Query(), InventoryReservation.class))
        .then(users.save(user("alice")))
        .doOnNext(saved -> alice = saved)
        .then(users.save(user("bob"))).then()).verifyComplete();
  }

  @Test
  void shouldAdvanceCreatedAcceptedPreparingReadyWithOrderedAudit() {
    save(order("O1", alice));
    assertStatus(workflow.changeStatus("O1", OrderStatus.DELIVERED,
        0L, "skip stages", kitchen()), HttpStatus.CONFLICT);
    assertStatus(workflow.changeStatus("O1", OrderStatus.DELIVERED,
        0L, "customer", customer("alice")), HttpStatus.FORBIDDEN);

    StepVerifier.create(workflow.claimNext(kitchen()))
        .assertNext(saved -> assertEquals(1L, saved.getVersion()))
        .verifyComplete();
    StepVerifier.create(workflow.changeStatus("O1", OrderStatus.PREPARING,
        1L, "Started cooking", kitchen()))
        .assertNext(saved -> assertEquals(2L, saved.getVersion()))
        .verifyComplete();
    StepVerifier.create(workflow.changeStatus("O1", OrderStatus.READY,
        2L, "Ready for dispatch", kitchen()))
        .assertNext(saved -> assertEquals(3L, saved.getVersion()))
        .verifyComplete();

    StepVerifier.create(orders.findById("O1"))
        .assertNext(saved -> {
          assertEquals(OrderStatus.READY, saved.getStatus());
          assertEquals("alice", saved.getUser().getUsername());
          assertEquals(4, saved.getStatusHistory().size());
          assertEquals(OrderStatus.CREATED,
              saved.getStatusHistory().get(0).getTo());
          assertEquals(OrderStatus.ACCEPTED,
              saved.getStatusHistory().get(1).getTo());
          assertEquals(OrderStatus.PREPARING,
              saved.getStatusHistory().get(2).getTo());
          assertEquals(OrderStatus.READY,
              saved.getStatusHistory().get(3).getTo());
          assertEquals("ROLE_KITCHEN",
              saved.getStatusHistory().get(3).getActorRole());
          assertEquals("KITCHEN_API",
              saved.getStatusHistory().get(3).getOrigin());
          assertEquals("Ready for dispatch",
              saved.getStatusHistory().get(3).getReason());
          for (int i = 1; i < saved.getStatusHistory().size(); i++) {
            assertTrue(saved.getStatusHistory().get(i).getChangedAt()
                .after(saved.getStatusHistory().get(i - 1).getChangedAt()));
          }
        }).verifyComplete();
  }

  @Test
  void shouldRestrictDeliveryToAdminAndMakeRepeatIdempotent() {
    TacoOrder ready = order("O2", alice);
    ready.setStatus(OrderStatus.READY);
    save(ready);

    assertStatus(workflow.changeStatus("O2",
        OrderStatus.OUT_FOR_DELIVERY, 0L, "dispatch", kitchen()),
        HttpStatus.FORBIDDEN);
    StepVerifier.create(workflow.changeStatus("O2",
        OrderStatus.OUT_FOR_DELIVERY, 0L, "dispatch", admin()))
        .assertNext(saved -> assertEquals(OrderStatus.OUT_FOR_DELIVERY,
            saved.getStatus())).verifyComplete();
    StepVerifier.create(workflow.changeStatus("O2", OrderStatus.DELIVERED,
        1L, "delivered", admin()))
        .assertNext(saved -> assertEquals(OrderStatus.DELIVERED,
            saved.getStatus())).verifyComplete();
    StepVerifier.create(workflow.changeStatus("O2", OrderStatus.DELIVERED,
        1L, "retry", admin()))
        .assertNext(saved -> {
          assertEquals(2L, saved.getVersion());
          assertEquals(3, saved.getStatusHistory().size());
        }).verifyComplete();
  }

  @Test
  void shouldRejectStaleVersionAndConcurrentMongoSave() {
    save(order("O3", alice));
    AtomicReference<TacoOrder> first = new AtomicReference<>();
    AtomicReference<TacoOrder> second = new AtomicReference<>();
    StepVerifier.create(Mono.zip(orders.findById("O3"),
        orders.findById("O3")))
        .assertNext(pair -> {
          first.set(pair.getT1());
          second.set(pair.getT2());
        }).verifyComplete();

    StepVerifier.create(workflow.claimNext(kitchen()))
        .assertNext(saved -> assertEquals(1L, saved.getVersion()))
        .verifyComplete();
    assertStatus(workflow.changeStatus("O3", OrderStatus.PREPARING,
        0L, "stale", kitchen()), HttpStatus.CONFLICT);

    first.get().setStatus(OrderStatus.ACCEPTED);
    StepVerifier.create(orders.save(first.get()))
        .expectError(OptimisticLockingFailureException.class).verify();
    second.get().setStatus(OrderStatus.READY);
    StepVerifier.create(orders.save(second.get()))
        .expectError(OptimisticLockingFailureException.class).verify();
  }

  @Test
  void shouldCancelOnlyOwnEarlyOrderAndRestoreInventoryOnce() {
    StepVerifier.create(ingredients.save(new Ingredient("WRAP", "Wrap",
        Ingredient.Type.WRAP, new BigDecimal("1.00"), true, 5, 1)))
        .expectNextCount(1).verifyComplete();
    TacoOrder order = order("O4", alice);
    Taco taco = new Taco();
    taco.setIngredients(Collections.singletonList(
        new Ingredient("WRAP", "Wrap", Ingredient.Type.WRAP)));
    OrderLine line = new OrderLine();
    line.setTaco(taco);
    line.setQuantity(2);
    order.setItems(Collections.singletonList(line));
    StepVerifier.create(inventory.reserve(order)
        .flatMap(reservation -> orders.save(order)
            .flatMap(saved -> inventory.accept(reservation.getId(),
                saved.getId()).thenReturn(saved))))
        .expectNextCount(1).verifyComplete();
    assertStock(3);

    assertStatus(workflow.cancel("O4", 0L, "not mine", customer("bob")),
        HttpStatus.FORBIDDEN);
    StepVerifier.create(workflow.cancel("O4", 0L, "Changed my mind",
        customer("alice")))
        .assertNext(saved -> {
          assertEquals(OrderStatus.CANCELLED, saved.getStatus());
          assertEquals(1L, saved.getVersion());
          assertEquals(2, saved.getStatusHistory().size());
          assertEquals("CUSTOMER_API",
              saved.getStatusHistory().get(1).getOrigin());
        }).verifyComplete();
    assertStock(5);
    StepVerifier.create(workflow.cancel("O4", 0L, "retry",
        customer("alice")))
        .assertNext(saved -> {
          assertEquals(1L, saved.getVersion());
          assertEquals(2, saved.getStatusHistory().size());
        }).verifyComplete();
    assertStock(5);
    StepVerifier.create(orders.findById("O4"))
        .assertNext(saved -> assertEquals(OrderStatus.CANCELLED,
            saved.getStatus())).verifyComplete();
  }

  @Test
  void shouldRejectCancellationAfterPreparation() {
    TacoOrder preparing = order("O5", alice);
    preparing.setStatus(OrderStatus.PREPARING);
    save(preparing);
    assertStatus(workflow.cancel("O5", 0L, "too late",
        customer("alice")), HttpStatus.CONFLICT);
    StepVerifier.create(orders.findById("O5"))
        .assertNext(saved -> assertEquals(OrderStatus.PREPARING,
            saved.getStatus())).verifyComplete();
  }

  private void assertStock(int stock) {
    StepVerifier.create(ingredients.findById("WRAP"))
        .assertNext(ingredient -> assertEquals(stock,
            ingredient.getStockOnHand())).verifyComplete();
  }

  private void save(TacoOrder order) {
    StepVerifier.create(orders.save(order))
        .assertNext(saved -> assertEquals(0L, saved.getVersion()))
        .verifyComplete();
  }

  private TacoOrder order(String id, User owner) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUser(owner);
    order.setStatusHistory(Collections.singletonList(
        new OrderStatusChange(null, OrderStatus.CREATED, "alice",
            "ROLE_USER", new Date(1), "HTTP_API", "Order created")));
    return order;
  }

  private User user(String username) {
    return new User(username, "encoded", username, "Street", "City",
        "ST", "12345", "5551234", username + "@example.com");
  }

  private Authentication customer(String username) {
    return auth(username, "ROLE_USER");
  }

  private Authentication kitchen() {
    return auth("cook", "ROLE_KITCHEN");
  }

  private Authentication admin() {
    return auth("admin", "ROLE_ADMIN");
  }

  private Authentication auth(String name, String role) {
    return new UsernamePasswordAuthenticationToken(name, "unused",
        AuthorityUtils.createAuthorityList(role));
  }

  private void assertStatus(Mono<?> action, HttpStatus expected) {
    StepVerifier.create(action).expectErrorSatisfies(error -> {
      assertTrue(error instanceof ResponseStatusException);
      assertEquals(expected, ((ResponseStatusException) error).getStatus());
    }).verify();
  }
}
