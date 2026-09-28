package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;

import javax.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.error.ApiExceptionHandler;
import tacos.api.mapper.OrderMapper;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.payment.PaymentMethodService;
import tacos.pricing.CouponProperties;
import tacos.pricing.CouponProperties.CouponRule;
import tacos.pricing.CouponService;
import tacos.pricing.CouponType;
import tacos.pricing.OrderPricingService;

class OrderCouponControllerTest {

  private OrderRepository orderRepo;
  private IngredientRepository ingredientRepo;
  private UserRepository userRepo;
  private PaymentMethodService paymentMethodService;
  private OrderMessagingService messaging;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    orderRepo = mock(OrderRepository.class);
    ingredientRepo = mock(IngredientRepository.class);
    userRepo = mock(UserRepository.class);
    paymentMethodService = mock(PaymentMethodService.class);
    messaging = mock(OrderMessagingService.class);

    CouponProperties properties = new CouponProperties();
    properties.getCodes().put("SAVE10", percentageRule());
    CouponService couponService = new CouponService(properties,
        Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC),
        "USD");
    OrderPricingService pricingService =
        new OrderPricingService(ingredientRepo, 10, "USD");
    InventoryService inventoryService = mock(InventoryService.class);
    InventoryReservation reservation = mock(InventoryReservation.class);
    when(reservation.getId()).thenReturn("RESERVATION-ID");
    when(inventoryService.reserve(any(TacoOrder.class)))
        .thenReturn(Mono.just(reservation));
    when(inventoryService.accept(any(String.class), any(String.class)))
        .thenReturn(Mono.empty());
    OrderService orderService = new OrderService(orderRepo,
        mock(EmailOrderService.class), messaging, userRepo,
        mock(Validator.class), paymentMethodService, pricingService,
        couponService, inventoryService);
    mvc = MockMvcBuilders.standaloneSetup(
        new OrderApiController(orderService, new OrderMapper()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void shouldPersistAppliedCouponOutcomeWithoutExposingItsCode() throws Exception {
    User owner = owner();
    when(userRepo.findByUsername("alice")).thenReturn(Mono.just(owner));
    when(paymentMethodService.findOwned(eq("PAYMENT-ID"), any(Authentication.class)))
        .thenReturn(Mono.just(new PaymentMethod(
            owner, "tok_test", "VISA", "0002", "12/99")));
    when(ingredientRepo.findById("WRAP"))
        .thenReturn(Mono.just(ingredient("WRAP", "1.10")));
    when(ingredientRepo.findById("SLSA"))
        .thenReturn(Mono.just(ingredient("SLSA", "0.35")));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(invocation -> {
      TacoOrder order = invocation.getArgument(0);
      order.setId("ORDER-ID");
      return Mono.just(order);
    });

    perform(post("/api/orders").content(validOrder()), user())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.subtotal").value(2.90))
        .andExpect(jsonPath("$.discount").value(0.29))
        .andExpect(jsonPath("$.total").value(2.61))
        .andExpect(jsonPath("$.couponApplied").value(true))
        .andExpect(jsonPath("$.couponCode").doesNotExist());

    ArgumentCaptor<TacoOrder> saved = ArgumentCaptor.forClass(TacoOrder.class);
    verify(orderRepo).save(saved.capture());
    assertEquals("SAVE10", saved.getValue().getCouponCode());
    assertEquals(new BigDecimal("0.29"), saved.getValue().getDiscount());
    assertEquals(new BigDecimal("2.61"), saved.getValue().getTotal());
    verify(messaging).sendOrder(saved.getValue());
  }

  private ResultActions perform(
      MockHttpServletRequestBuilder request, Principal principal) throws Exception {
    MvcResult pending = mvc.perform(request.principal(principal)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(request().asyncStarted())
        .andReturn();
    return mvc.perform(asyncDispatch(pending));
  }

  private String validOrder() {
    return "{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"Street\","
        + "\"deliveryCity\":\"City\",\"deliveryState\":\"ST\","
        + "\"deliveryZip\":\"00000\",\"paymentMethodId\":\"PAYMENT-ID\","
        + "\"couponCode\":\"save10\",\"items\":[{\"taco\":{"
        + "\"name\":\"Valid taco\",\"ingredientIds\":[\"WRAP\",\"SLSA\"]},"
        + "\"quantity\":2}]}";
  }

  private CouponRule percentageRule() {
    CouponRule rule = new CouponRule();
    rule.setType(CouponType.PERCENTAGE);
    rule.setValue(new BigDecimal("10.00"));
    rule.setStartsOn(LocalDate.of(2026, 1, 1));
    rule.setExpiresOn(LocalDate.of(2026, 12, 31));
    rule.setMinimumSubtotal(BigDecimal.ZERO);
    return rule;
  }

  private Ingredient ingredient(String id, String price) {
    return new Ingredient(id, id + " ingredient", Ingredient.Type.WRAP,
        new BigDecimal(price), true, 20, 5);
  }

  private User owner() {
    User owner = new User("alice", "N/A", "Alice", "Street", "City", "ST",
        "00000", "0000000000", "alice@example.test");
    owner.setId("USER-ID");
    return owner;
  }

  private UsernamePasswordAuthenticationToken user() {
    return new UsernamePasswordAuthenticationToken("alice", "N/A",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
