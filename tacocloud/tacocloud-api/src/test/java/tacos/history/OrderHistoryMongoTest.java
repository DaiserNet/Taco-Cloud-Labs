package tacos.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

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
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderHistoryPageResponse;
import tacos.api.dto.OrderSummaryResponse;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;

@SpringBootTest(classes = OrderHistoryMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.config.name=tc23-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc23-test",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.mongodb.embedded.version=3.5.5",
        "tacocloud.order-history.max-page-size=2"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderHistoryMongoTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = OrderRepository.class)
  @Import({OrderHistoryService.class, OrderMapper.class})
  static class TestApplication {
  }

  @Autowired private OrderHistoryService history;
  @Autowired private OrderRepository orderRepo;
  @Autowired private UserRepository userRepo;
  @Autowired private ReactiveMongoTemplate mongo;

  private User alice;
  private User bob;

  @BeforeEach
  void setUp() {
    StepVerifier.create(orderRepo.deleteAll().then(userRepo.deleteAll())
        .then(userRepo.save(user("alice")))
        .doOnNext(saved -> alice = saved)
        .then(userRepo.save(user("bob")))
        .doOnNext(saved -> bob = saved)
        .then()).verifyComplete();
  }

  @Test
  void shouldListOnlyAuthenticatedUsersOrders() {
    save(order("A", alice, 1000, OrderStatus.PLACED),
        order("B", bob, 2000, OrderStatus.PLACED));

    StepVerifier.create(history.myOrders(0, 2, auth("alice", "ROLE_USER")))
        .assertNext(page -> {
          assertEquals(Arrays.asList("A"), ids(page));
          assertEquals(1L, page.getTotalElements());
        }).verifyComplete();
    StepVerifier.create(history.myOrder("A", auth("alice", "ROLE_USER")))
        .assertNext(detail -> assertEquals("A", detail.getId()))
        .verifyComplete();
    assertStatus(history.myOrder("B", auth("alice", "ROLE_USER")),
        HttpStatus.NOT_FOUND);
  }

  @Test
  void shouldOrderByPlacedAtThenIdAndReturnEmptyOutOfRangePage() {
    save(order("C", alice, 3000, OrderStatus.PLACED),
        order("A", alice, 1000, OrderStatus.PLACED),
        order("B", alice, 3000, OrderStatus.PLACED));

    assertPage(0, "B", 3L, 3L);
    assertPage(1, "C", 3L, 3L);
    assertPage(2, "A", 3L, 3L);
    assertPage(3, null, 3L, 3L);
    assertStatus(history.myOrders(-1, 1, auth("alice", "ROLE_USER")),
        HttpStatus.BAD_REQUEST);
    assertStatus(history.myOrders(0, 3, auth("alice", "ROLE_USER")),
        HttpStatus.BAD_REQUEST);
  }

  @Test
  void shouldAllowAdminToAuditAllOrdersWithIntersectedFilters() {
    save(order("A", alice, 1000, OrderStatus.PLACED),
        order("B", bob, 2000, OrderStatus.PREPARING),
        order("C", alice, 3000, OrderStatus.PREPARING));
    Authentication admin = auth("auditor", "ROLE_ADMIN");

    StepVerifier.create(history.adminOrders(0, 2, null, null, admin))
        .assertNext(page -> {
          assertEquals(Arrays.asList("C", "B"), ids(page));
          assertEquals(3L, page.getTotalElements());
          assertEquals(2L, page.getTotalPages());
        }).verifyComplete();
    StepVerifier.create(history.adminOrders(0, 2, alice.getId(),
        OrderStatus.PREPARING, admin))
        .assertNext(page -> assertEquals(Arrays.asList("C"), ids(page)))
        .verifyComplete();
    StepVerifier.create(history.adminOrder("B", admin))
        .assertNext(detail -> assertEquals("B", detail.getId()))
        .verifyComplete();
    assertStatus(history.adminOrders(0, 2, null, null,
        auth("alice", "ROLE_USER")), HttpStatus.FORBIDDEN);
    assertStatus(history.myOrders(0, 2, admin), HttpStatus.FORBIDDEN);
  }

  @Test
  void shouldCreateIndexesForOwnerAndStableHistorySort() {
    StepVerifier.create(mongo.indexOps(TacoOrder.class).getIndexInfo()
        .map(index -> index.getName()).collectList())
        .assertNext(names -> {
          assertTrue(names.contains("order_owner_placed_id"));
          assertTrue(names.contains("order_placed_id"));
          assertTrue(names.contains("order_status_placed_id"));
        }).verifyComplete();
  }

  private void assertPage(int number, String expectedId,
      long total, long pages) {
    StepVerifier.create(history.myOrders(number, 1, auth("alice", "ROLE_USER")))
        .assertNext(result -> {
          assertEquals(expectedId == null ? Collections.emptyList()
              : Arrays.asList(expectedId), ids(result));
          assertEquals(total, result.getTotalElements());
          assertEquals(pages, result.getTotalPages());
        }).verifyComplete();
  }

  private void assertStatus(Mono<?> action, HttpStatus status) {
    StepVerifier.create(action).expectErrorSatisfies(error ->
        assertEquals(status, ((ResponseStatusException) error).getStatus()))
        .verify();
  }

  private List<String> ids(OrderHistoryPageResponse page) {
    return page.getContent().stream().map(OrderSummaryResponse::getId)
        .collect(Collectors.toList());
  }

  private void save(TacoOrder... orders) {
    StepVerifier.create(Flux.fromArray(orders).concatMap(orderRepo::save).then())
        .verifyComplete();
  }

  private TacoOrder order(String id, User owner, long placedAt,
      OrderStatus status) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUser(owner);
    order.setPlacedAt(new Date(placedAt));
    order.setStatus(status);
    order.setPaymentMethodId("secret-token");
    order.setTotal(new BigDecimal("12.50"));
    return order;
  }

  private User user(String username) {
    return new User(username, "encoded-secret", username, "Street", "City",
        "ST", "12345", "5551234", username + "@example.com");
  }

  private Authentication auth(String username, String role) {
    return new UsernamePasswordAuthenticationToken(username, "unused",
        AuthorityUtils.createAuthorityList(role));
  }
}
