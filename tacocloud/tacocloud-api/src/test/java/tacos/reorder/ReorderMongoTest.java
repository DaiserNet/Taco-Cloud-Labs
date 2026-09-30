package tacos.reorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import javax.validation.Validation;
import javax.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
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
import tacos.PaymentMethod;
import tacos.ReorderAttempt;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.ReorderQuoteResponse;
import tacos.api.dto.ReorderRequest;
import tacos.api.mapper.OrderMapper;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.design.TacoDesignTestSupport;
import tacos.design.TacoDesignException;
import tacos.history.OrderHistoryService;
import tacos.inventory.InsufficientStockException;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.messaging.OrderMessagingService;
import tacos.payment.PaymentMethodService;
import tacos.pricing.CouponProperties;
import tacos.pricing.CouponProperties.CouponRule;
import tacos.pricing.CouponService;
import tacos.pricing.CouponType;
import tacos.pricing.CouponValidationException;
import tacos.pricing.OrderPricingService;
import tacos.web.api.EmailOrderService;
import tacos.web.api.OrderService;
import tacos.web.api.OrderOutboxTestSupport;

@SpringBootTest(classes = ReorderMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.config.name=tc24-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc24-test",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.mongodb.embedded.version=3.5.5"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReorderMongoTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = OrderRepository.class)
  static class TestApplication {
  }

  @Autowired private ReactiveMongoTemplate mongo;
  @Autowired private OrderRepository orderRepo;
  @Autowired private UserRepository userRepo;
  @Autowired private IngredientRepository ingredientRepo;

  private ReorderService reorders;
  private OrderMessagingService messaging;
  private PaymentMethodService payments;
  private User alice;

  @BeforeEach
  void setUp() {
    StepVerifier.create(mongo.remove(new Query(), ReorderAttempt.class)
        .then(mongo.remove(new Query(), InventoryReservation.class))
        .then(orderRepo.deleteAll()).then(ingredientRepo.deleteAll())
        .then(userRepo.deleteAll())
        .then(userRepo.save(user("alice")))
        .doOnNext(saved -> alice = saved)
        .then(userRepo.save(user("bob")))
        .then(ingredientRepo.save(ingredient("WRAP",
            Ingredient.Type.WRAP, "1.00", 10)))
        .then(ingredientRepo.save(ingredient("SLSA",
            Ingredient.Type.SAUCE, "0.50", 10)))
        .then(Mono.defer(() -> orderRepo.save(sourceOrder()))).then())
        .verifyComplete();

    OrderMapper mapper = new OrderMapper();
    Validator validator = Validation.buildDefaultValidatorFactory()
        .getValidator();
    payments = mock(PaymentMethodService.class);
    when(payments.findOwned(eq("PAY1"), any(Authentication.class)))
        .thenReturn(Mono.just(new PaymentMethod(alice, "tok_current",
            "VISA", "4242", "12/99")));
    messaging = mock(OrderMessagingService.class);
    OrderPricingService pricing = new OrderPricingService(
        TacoDesignTestSupport.validator(ingredientRepo), 10, "USD");
    CouponService coupons = new CouponService(couponProperties(),
        Clock.systemUTC(), "USD");
    InventoryService inventory = new InventoryService(mongo);
    OrderService orders = new OrderService(orderRepo,
        mock(EmailOrderService.class),
        OrderOutboxTestSupport.commitUsing(orderRepo, inventory),
        userRepo, validator,
        payments, pricing, coupons, inventory,
        new tacos.observability.OrderMetrics(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), "noop"));
    OrderHistoryService history = new OrderHistoryService(mongo, userRepo,
        mapper, 50);
    reorders = new ReorderService(history, mapper, validator, payments,
        pricing, coupons, orders, orderRepo, mongo);
  }

  @Test
  void shouldCreateFreshOrderAtCurrentPriceAndReplayWithoutNewEffects() {
    StepVerifier.create(ingredientRepo.findById("WRAP")
        .flatMap(ingredient -> {
          ingredient.setUnitPrice(new BigDecimal("1.25"));
          ingredient.setName("Fresh wrap");
          return ingredientRepo.save(ingredient);
        })).expectNextCount(1).verifyComplete();

    ReorderQuoteResponse quote = quote(request(null));
    assertEquals(new BigDecimal("3.00"), quote.getOriginalTotal());
    assertEquals(new BigDecimal("3.50"), quote.getQuotedTotal());
    assertEquals(new BigDecimal("0.50"), quote.getPriceDifference());
    assertEquals(1, quote.getIngredientDifferences().size());
    assertEquals("WRAP", quote.getIngredientDifferences().get(0)
        .getIngredientId());
    assertTrue(quote.isConfirmationRequired());
    assertCountAndStock(1, 10);
    verifyNoInteractions(messaging);

    ReorderRequest confirmation = request(quote.getQuoteFingerprint());
    StepVerifier.create(reorders.confirm("OLD", confirmation,
        "attempt-one", auth("alice")))
        .assertNext(result -> {
          assertFalse(result.isReplayed());
          assertNotEquals("OLD", result.getOrder().getId());
          assertTrue(result.getOrder().getPlacedAt().after(new Date(0)));
          assertEquals(OrderStatus.CREATED, result.getOrder().getStatus());
          assertEquals("REORDER", result.getOrder().getStatusHistory()
              .get(0).getOrigin());
          assertEquals(new BigDecimal("3.50"), result.getOrder().getTotal());
          assertEquals("4242", result.getOrder().getPaymentLast4());
        }).verifyComplete();
    assertCountAndStock(2, 8);
    StepVerifier.create(orderRepo.findById("OLD"))
        .assertNext(original -> {
          assertEquals(new Date(0), original.getPlacedAt());
          assertEquals(OrderStatus.PREPARING, original.getStatus());
          assertEquals(new BigDecimal("3.00"), original.getTotal());
          assertEquals("OLD-PAY", original.getPaymentMethodId());
          assertEquals("Original wrap", original.getItems().get(0).getTaco()
              .getIngredients().get(0).getName());
        }).verifyComplete();

    StepVerifier.create(ingredientRepo.findById("WRAP")
        .flatMap(ingredient -> {
          ingredient.setUnitPrice(new BigDecimal("9.99"));
          return ingredientRepo.save(ingredient);
        })).expectNextCount(1).verifyComplete();
    StepVerifier.create(reorders.confirm("OLD", confirmation,
        "attempt-one", auth("alice")))
        .assertNext(result -> {
          assertTrue(result.isReplayed());
          assertEquals(new BigDecimal("3.50"), result.getOrder().getTotal());
        }).verifyComplete();
    assertCountAndStock(2, 8);
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldRejectExhaustedStockBeforeCreatingOrPublishing() {
    StepVerifier.create(ingredientRepo.findById("SLSA")
        .flatMap(ingredient -> {
          ingredient.setStockOnHand(1);
          return ingredientRepo.save(ingredient);
        })).expectNextCount(1).verifyComplete();

    StepVerifier.create(reorders.quote("OLD", request(null), auth("alice")))
        .expectErrorMatches(error -> error instanceof InsufficientStockException
            && "SLSA".equals(((InsufficientStockException) error)
                .getIngredientId()))
        .verify();
    assertCountAndStock(1, 10);
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldRejectStockExhaustedAfterQuoteBeforeConfirmation() {
    ReorderQuoteResponse quote = quote(request(null));
    StepVerifier.create(ingredientRepo.findById("SLSA")
        .flatMap(ingredient -> {
          ingredient.setStockOnHand(1);
          return ingredientRepo.save(ingredient);
        })).expectNextCount(1).verifyComplete();

    StepVerifier.create(reorders.confirm("OLD",
        request(quote.getQuoteFingerprint()), "stock-changed",
        auth("alice")))
        .expectError(InsufficientStockException.class).verify();
    assertCountAndStock(1, 10);
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldApplyCurrentDesignRulesBeforeCreatingOrder() {
    StepVerifier.create(ingredientRepo.findById("SLSA")
        .flatMap(ingredient -> {
          ingredient.setAvailable(false);
          return ingredientRepo.save(ingredient);
        })).expectNextCount(1).verifyComplete();

    StepVerifier.create(reorders.quote("OLD", request(null), auth("alice")))
        .expectError(TacoDesignException.class).verify();
    assertCountAndStock(1, 10);
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldRequireCurrentOwnedPaymentMethod() {
    when(payments.findOwned(eq("UNOWNED"), any(Authentication.class)))
        .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    ReorderRequest request = request(null);
    request.setPaymentMethodId("UNOWNED");

    StepVerifier.create(reorders.quote("OLD", request, auth("alice")))
        .expectErrorMatches(error -> error instanceof ResponseStatusException
            && ((ResponseStatusException) error).getStatus()
                == HttpStatus.NOT_FOUND)
        .verify();
    assertCountAndStock(1, 10);
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldRejectForeignOrderAndStaleQuote() {
    StepVerifier.create(reorders.quote("OLD", request(null), auth("bob")))
        .expectErrorMatches(error -> error instanceof ResponseStatusException
            && ((ResponseStatusException) error).getStatus()
                == HttpStatus.NOT_FOUND)
        .verify();
    ReorderQuoteResponse quote = quote(request(null));
    StepVerifier.create(ingredientRepo.findById("WRAP")
        .flatMap(ingredient -> {
          ingredient.setUnitPrice(new BigDecimal("2.00"));
          return ingredientRepo.save(ingredient);
        })).expectNextCount(1).verifyComplete();

    StepVerifier.create(reorders.confirm("OLD",
        request(quote.getQuoteFingerprint()), "attempt-two", auth("alice")))
        .expectErrorMatches(error -> error instanceof ResponseStatusException
            && ((ResponseStatusException) error).getStatus()
                == HttpStatus.CONFLICT)
        .verify();
    assertCountAndStock(1, 10);
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldRevalidateSelectedCouponAndRejectDifferentKeyPayload() {
    ReorderRequest withCoupon = request(null);
    withCoupon.setCouponCode("SAVE10");
    ReorderQuoteResponse quote = quote(withCoupon);
    assertTrue(quote.isCouponApplied());
    assertEquals(new BigDecimal("2.70"), quote.getQuotedTotal());
    ReorderRequest confirm = request(quote.getQuoteFingerprint());
    confirm.setCouponCode("SAVE10");
    StepVerifier.create(reorders.confirm("OLD", confirm, "attempt-three",
        auth("alice"))).expectNextCount(1).verifyComplete();

    StepVerifier.create(reorders.confirm("OLD",
        request(quote.getQuoteFingerprint()), "attempt-three", auth("alice")))
        .expectErrorMatches(error -> error instanceof ResponseStatusException
            && ((ResponseStatusException) error).getStatus()
                == HttpStatus.CONFLICT)
        .verify();
    assertCountAndStock(2, 8);
    verifyNoInteractions(messaging);

    ReorderRequest invalidCoupon = request(null);
    invalidCoupon.setCouponCode("EXPIRED");
    StepVerifier.create(reorders.quote("OLD", invalidCoupon, auth("alice")))
        .expectError(CouponValidationException.class).verify();
  }

  private ReorderQuoteResponse quote(ReorderRequest request) {
    AtomicReference<ReorderQuoteResponse> response = new AtomicReference<>();
    StepVerifier.create(reorders.quote("OLD", request, auth("alice")))
        .assertNext(response::set).verifyComplete();
    return response.get();
  }

  private void assertCountAndStock(long count, int wrapStock) {
    StepVerifier.create(orderRepo.count()).expectNext(count).verifyComplete();
    StepVerifier.create(ingredientRepo.findById("WRAP"))
        .assertNext(ingredient -> assertEquals(wrapStock,
            ingredient.getStockOnHand())).verifyComplete();
  }

  private ReorderRequest request(String fingerprint) {
    ReorderRequest request = new ReorderRequest();
    request.setPaymentMethodId("PAY1");
    request.setQuoteFingerprint(fingerprint);
    return request;
  }

  private TacoOrder sourceOrder() {
    Ingredient oldWrap = ingredient("WRAP", Ingredient.Type.WRAP, "1.00", 10);
    oldWrap.setName("Original wrap");
    Ingredient oldSalsa = ingredient("SLSA", Ingredient.Type.SAUCE, "0.50", 10);
    Taco taco = new Taco();
    taco.setName("Old taco");
    taco.setIngredients(Arrays.asList(oldWrap, oldSalsa));
    OrderLine line = new OrderLine();
    line.setTaco(taco);
    line.setQuantity(2);
    line.setUnitPriceAtPurchase(new BigDecimal("1.50"));
    line.setSubtotal(new BigDecimal("3.00"));
    TacoOrder source = new TacoOrder();
    source.setId("OLD");
    source.setPlacedAt(new Date(0));
    source.setStatus(OrderStatus.PREPARING);
    source.setUser(alice);
    source.setDeliveryName("Alice");
    source.setDeliveryStreet("Street");
    source.setDeliveryCity("City");
    source.setDeliveryState("ST");
    source.setDeliveryZip("12345");
    source.setPaymentMethodId("OLD-PAY");
    source.setInventoryReservationId("OLD-RESERVATION");
    source.setItems(Collections.singletonList(line));
    source.setSubtotal(new BigDecimal("3.00"));
    source.setTotal(new BigDecimal("3.00"));
    return source;
  }

  private Ingredient ingredient(String id, Ingredient.Type type,
      String price, int stock) {
    return new Ingredient(id, id + " ingredient", type,
        new BigDecimal(price), true, stock, 2);
  }

  private CouponProperties couponProperties() {
    CouponRule rule = new CouponRule();
    rule.setType(CouponType.PERCENTAGE);
    rule.setValue(new BigDecimal("10"));
    rule.setStartsOn(LocalDate.now().minusDays(1));
    rule.setExpiresOn(LocalDate.now().plusDays(1));
    CouponProperties properties = new CouponProperties();
    properties.setCodes(Collections.singletonMap("SAVE10", rule));
    return properties;
  }

  private User user(String username) {
    return new User(username, "encoded", username, "Street", "City",
        "ST", "12345", "5551234", username + "@example.com");
  }

  private Authentication auth(String username) {
    return new UsernamePasswordAuthenticationToken(username, "unused",
        AuthorityUtils.createAuthorityList("ROLE_USER"));
  }
}
