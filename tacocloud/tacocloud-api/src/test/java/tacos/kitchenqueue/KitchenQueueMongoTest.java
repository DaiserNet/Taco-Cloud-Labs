package tacos.kitchenqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.OrderLine;
import tacos.OrderStatus;
import tacos.OrderStatusChange;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.KitchenOrderResponse;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;
import tacos.observability.OrderMetrics;
import tacos.workflow.OrderWorkflowService;

@SpringBootTest(classes = KitchenQueueMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.config.name=tc26-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc26-test",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.mongodb.embedded.version=3.5.5"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KitchenQueueMongoTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = OrderRepository.class)
  @Import({OrderWorkflowService.class, InventoryService.class,
      KitchenQueueService.class, KitchenEtaCalculator.class})
  static class TestApplication {
  }

  @Autowired private OrderRepository orders;
  @MockBean private OrderMetrics metrics;
  @Autowired private OrderWorkflowService workflow;
  @Autowired private KitchenQueueService queue;
  @Autowired private ReactiveMongoTemplate mongo;

  @BeforeEach
  void setUp() {
    StepVerifier.create(orders.deleteAll()).verifyComplete();
  }

  @Test
  void shouldListCreatedOrdersInStableFifoOrderAndClaimThem() {
    save(order("B", 1000, 1, 2));
    save(order("A", 1000, 1, 2));
    save(order("C", 2000, 1, 2));

    StepVerifier.create(queue.queue(kitchen("cook1")).collectList())
        .assertNext(items -> {
          assertEquals(Arrays.asList("A", "B", "C"), ids(items));
          assertTrue(items.get(1).getEstimatedPrepMinutes()
              > items.get(0).getEstimatedPrepMinutes());
        }).verifyComplete();

    assertClaim("cook1", "A");
    assertClaim("cook2", "B");
    assertClaim("cook3", "C");
    StepVerifier.create(workflow.claimNext(kitchen("cook4")))
        .verifyComplete();
  }

  @Test
  void shouldClaimEachOrderOnceUnderConcurrentRequests() {
    save(order("A", 1000, 1, 2));
    save(order("B", 2000, 1, 2));

    StepVerifier.create(Flux.merge(workflow.claimNext(kitchen("cook1")),
        workflow.claimNext(kitchen("cook2"))).collectList())
        .assertNext(claimed -> {
          assertEquals(2, claimed.size());
          assertNotEquals(claimed.get(0).getId(), claimed.get(1).getId());
          assertEquals(OrderStatus.ACCEPTED, claimed.get(0).getStatus());
          assertEquals(OrderStatus.ACCEPTED, claimed.get(1).getStatus());
          assertNotEquals(claimed.get(0).getStationId(),
              claimed.get(1).getStationId());
        }).verifyComplete();

    StepVerifier.create(orders.findAll().collectList())
        .assertNext(all -> assertEquals(2, all.stream()
            .filter(order -> order.getStatus() == OrderStatus.ACCEPTED)
            .count())).verifyComplete();
  }

  @Test
  void shouldAllowOnlyOneConcurrentClaimPerStation() {
    save(order("A", 1000, 1, 2));
    save(order("B", 2000, 1, 2));

    StepVerifier.create(Flux.merge(
        claimOrConflict("cook1"), claimOrConflict("cook1"))
        .collectList())
        .assertNext(results -> {
          assertEquals(2, results.size());
          assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
        }).verifyComplete();
    StepVerifier.create(orders.findAll().collectList())
        .assertNext(all -> {
          assertEquals(1, all.stream().filter(order ->
              order.getStatus() == OrderStatus.ACCEPTED).count());
          assertEquals(1, all.stream().filter(order ->
              order.getStatus() == OrderStatus.CREATED).count());
        }).verifyComplete();
  }

  @Test
  void shouldRejectSecondClaimFromBusyStationAndFreeItAtReady() {
    save(order("A", 1000, 1, 2));
    save(order("B", 2000, 1, 2));
    assertClaim("cook1", "A");

    StepVerifier.create(workflow.claimNext(kitchen("cook1")))
        .expectErrorSatisfies(error -> assertConflict(error))
        .verify();
    StepVerifier.create(workflow.changeStatus("B", OrderStatus.ACCEPTED,
        0L, "bypass queue", kitchen("cook2")))
        .expectErrorSatisfies(this::assertConflict).verify();
    StepVerifier.create(workflow.changeStatus("A", OrderStatus.PREPARING,
        1L, "Started", kitchen("cook2")))
        .expectErrorSatisfies(error -> assertEquals(HttpStatus.FORBIDDEN,
            ((ResponseStatusException) error).getStatus()))
        .verify();
    StepVerifier.create(workflow.changeStatus("A", OrderStatus.PREPARING,
        1L, "Started", kitchen("cook1")))
        .expectNextCount(1).verifyComplete();
    StepVerifier.create(workflow.changeStatus("A", OrderStatus.READY,
        2L, "Ready", kitchen("cook1")))
        .assertNext(ready -> {
          assertEquals(OrderStatus.READY, ready.getStatus());
          assertEquals(null, ready.getActiveStationId());
        }).verifyComplete();
    assertClaim("cook1", "B");
  }

  @Test
  void shouldFreeStationWhenOwnerCancelsAcceptedOrder() {
    TacoOrder first = order("A", 1000, 1, 2);
    first.setUser(new User("alice", "encoded", "Alice", "Street",
        "City", "ST", "12345", "5551234", "alice@example.com"));
    save(first);
    save(order("B", 2000, 1, 2));
    assertClaim("cook1", "A");

    StepVerifier.create(workflow.cancel("A", 1L, "Changed my mind",
        customer())).assertNext(cancelled -> {
          assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
          assertEquals(null, cancelled.getActiveStationId());
        }).verifyComplete();
    assertClaim("cook1", "B");
  }

  @Test
  void shouldKeepSensitiveFieldsOutOfKitchenResponse() throws Exception {
    TacoOrder source = order("A", 1000, 2, 4);
    source.setDeliveryStreet("Secret Street");
    source.setPaymentLast4("9876");
    source.setPaymentMethodId("private-token");
    save(source);

    StepVerifier.create(queue.queue(kitchen("cook1")))
        .assertNext(item -> {
          try {
            String json = new ObjectMapper().writeValueAsString(item);
            assertTrue(json.contains("estimatedPrepMinutes"));
            assertTrue(json.contains("ingredientIds"));
            assertTrue(json.contains("ingredientNames"));
            assertFalse(json.contains("Secret Street"));
            assertFalse(json.contains("9876"));
            assertFalse(json.contains("private-token"));
            assertFalse(json.contains("deliveryStreet"));
            assertFalse(json.contains("payment"));
            assertFalse(json.contains("user"));
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        }).verifyComplete();
  }

  @Test
  void shouldKeepQueuePrivateAndCreateUniqueStationIndex() {
    StepVerifier.create(queue.queue(customer()).collectList())
        .expectErrorSatisfies(error -> assertEquals(HttpStatus.FORBIDDEN,
            ((ResponseStatusException) error).getStatus()))
        .verify();
    StepVerifier.create(mongo.indexOps(TacoOrder.class).getIndexInfo()
        .collectList())
        .assertNext(indexes -> assertTrue(indexes.stream()
            .anyMatch(index -> "order_active_station_unique".equals(
                index.getName()) && index.isUnique())))
        .verifyComplete();
  }

  private void assertClaim(String cook, String id) {
    StepVerifier.create(queue.claim(kitchen(cook)))
        .assertNext(claimed -> {
          assertEquals(id, claimed.getId());
          assertEquals(OrderStatus.ACCEPTED, claimed.getStatus());
          assertEquals("station:" + cook, claimed.getStationId());
          assertEquals(cook, claimed.getCookId());
          assertEquals(1L, claimed.getVersion());
        }).verifyComplete();
    StepVerifier.create(orders.findById(id))
        .assertNext(saved -> {
          assertEquals(2, saved.getStatusHistory().size());
          OrderStatusChange change = saved.getStatusHistory().get(1);
          assertEquals(OrderStatus.CREATED, change.getFrom());
          assertEquals(OrderStatus.ACCEPTED, change.getTo());
          assertEquals("KITCHEN_QUEUE", change.getOrigin());
          assertEquals("station:" + cook, saved.getActiveStationId());
        }).verifyComplete();
  }

  private void assertConflict(Throwable error) {
    assertTrue(error instanceof ResponseStatusException);
    assertEquals(HttpStatus.CONFLICT,
        ((ResponseStatusException) error).getStatus());
  }

  private reactor.core.publisher.Mono<String> claimOrConflict(String cook) {
    return workflow.claimNext(kitchen(cook)).map(TacoOrder::getId)
        .onErrorResume(ResponseStatusException.class, error -> {
          assertEquals(HttpStatus.CONFLICT, error.getStatus());
          return reactor.core.publisher.Mono.just("CONFLICT");
        });
  }

  private List<String> ids(List<KitchenOrderResponse> items) {
    return items.stream().map(KitchenOrderResponse::getId)
        .collect(Collectors.toList());
  }

  private void save(TacoOrder order) {
    StepVerifier.create(orders.save(order)).expectNextCount(1).verifyComplete();
  }

  private TacoOrder order(String id, long placedAt, int quantity,
      int ingredientCount) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setPlacedAt(new Date(placedAt));
    Taco taco = new Taco();
    taco.setName("Taco " + id);
    Ingredient[] ingredients = new Ingredient[ingredientCount];
    for (int i = 0; i < ingredientCount; i++) {
      ingredients[i] = new Ingredient("I" + i, "Ingredient " + i,
          Ingredient.Type.PROTEIN);
    }
    taco.setIngredients(Arrays.asList(ingredients));
    OrderLine line = new OrderLine();
    line.setTaco(taco);
    line.setQuantity(quantity);
    order.setItems(Collections.singletonList(line));
    order.setStatusHistory(Collections.singletonList(new OrderStatusChange(
        null, OrderStatus.CREATED, "alice", "ROLE_USER",
        new Date(placedAt), "HTTP_API", "Order created")));
    return order;
  }

  private Authentication kitchen(String username) {
    return new UsernamePasswordAuthenticationToken(username, "unused",
        AuthorityUtils.createAuthorityList("ROLE_KITCHEN"));
  }

  private Authentication customer() {
    return new UsernamePasswordAuthenticationToken("alice", "unused",
        AuthorityUtils.createAuthorityList("ROLE_USER"));
  }
}
