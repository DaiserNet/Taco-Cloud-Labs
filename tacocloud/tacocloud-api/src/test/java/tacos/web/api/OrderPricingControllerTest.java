package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.security.Principal;
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
import tacos.design.TacoDesignTestSupport;
import tacos.messaging.OrderMessagingService;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.payment.PaymentMethodService;
import tacos.pricing.CouponService;
import tacos.pricing.OrderPricingService;

class OrderPricingControllerTest {

  private OrderRepository orderRepo;
  private IngredientRepository ingredientRepo;
  private UserRepository userRepo;
  private PaymentMethodService paymentMethodService;
  private OrderMessagingService messaging;
  private InventoryService inventoryService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    orderRepo = mock(OrderRepository.class);
    ingredientRepo = mock(IngredientRepository.class);
    userRepo = mock(UserRepository.class);
    paymentMethodService = mock(PaymentMethodService.class);
    messaging = mock(OrderMessagingService.class);
    OrderPricingService pricingService =
        new OrderPricingService(TacoDesignTestSupport.validator(ingredientRepo),
            10, "USD");
    CouponService couponService = mock(CouponService.class);
    when(couponService.apply(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    inventoryService = mock(InventoryService.class);
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
  void shouldCreateOrderWithQuantityAndServerCalculatedTotals() throws Exception {
    prepareOwnerAndPayment();
    when(ingredientRepo.findById("WRAP"))
        .thenReturn(Mono.just(ingredient("WRAP", "1.10")));
    when(ingredientRepo.findById("SLSA"))
        .thenReturn(Mono.just(ingredient("SLSA", "0.35")));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(invocation -> {
      TacoOrder order = invocation.getArgument(0);
      order.setId("ORDER-ID");
      return Mono.just(order);
    });

    ResultActions response = perform(post("/api/orders")
        .content(validOrder(2)), user());

    response.andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("ORDER-ID"))
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.items[0].quantity").value(2))
        .andExpect(jsonPath("$.items[0].unitPriceAtPurchase").value(1.45))
        .andExpect(jsonPath("$.items[0].subtotal").value(2.90))
        .andExpect(jsonPath("$.subtotal").value(2.90))
        .andExpect(jsonPath("$.total").value(2.90));

    ArgumentCaptor<TacoOrder> savedOrder = ArgumentCaptor.forClass(TacoOrder.class);
    verify(orderRepo).save(savedOrder.capture());
    assertEquals(new BigDecimal("1.45"),
        savedOrder.getValue().getItems().get(0).getUnitPriceAtPurchase());
    verify(messaging).sendOrder(savedOrder.getValue());
  }

  @Test
  void shouldRejectClientSuppliedTotalBeforeAnyEffect() throws Exception {
    mvc.perform(post("/api/orders").principal(user())
            .contentType(MediaType.APPLICATION_JSON)
            .content(validOrder(2).replaceFirst("\\{", "{\"total\":0.01,")))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

    verifyNoInteractions(orderRepo, ingredientRepo, userRepo,
        paymentMethodService, messaging);
  }

  @Test
  void shouldRejectZeroQuantityBeforeAnyEffect() throws Exception {
    mvc.perform(post("/api/orders").principal(user())
            .contentType(MediaType.APPLICATION_JSON)
            .content(validOrder(0)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    verifyNoInteractions(orderRepo, ingredientRepo, userRepo,
        paymentMethodService, messaging);
  }

  @Test
  void shouldRejectQuantityAboveConfiguredMaximumWithoutSaving() throws Exception {
    prepareOwnerAndPayment();

    perform(post("/api/orders").content(validOrder(11)), user())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORDER_QUANTITY_INVALID"));

    verifyNoInteractions(ingredientRepo, messaging);
    verify(orderRepo, never()).save(any(TacoOrder.class));
  }

  @Test
  void shouldRejectInvalidDesignBeforeInventoryReservation() throws Exception {
    prepareOwnerAndPayment();
    when(ingredientRepo.findById("WRAP"))
        .thenReturn(Mono.just(ingredient("WRAP", "1.10")));

    perform(post("/api/orders").content(
        validOrder(1).replace("\"SLSA\"", "\"WRAP\"")), user())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TACO_DESIGN_INVALID"))
        .andExpect(jsonPath("$.violations[0].code").value("TACO_BASE_COUNT"));

    verify(inventoryService, never()).reserve(any(TacoOrder.class));
    verify(orderRepo, never()).save(any(TacoOrder.class));
    verify(messaging, never()).sendOrder(any(TacoOrder.class));
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

  private void prepareOwnerAndPayment() {
    User owner = owner();
    when(userRepo.findByUsername("alice")).thenReturn(Mono.just(owner));
    when(paymentMethodService.findOwned(eq("PAYMENT-ID"), any(Authentication.class)))
        .thenReturn(Mono.just(new PaymentMethod(
            owner, "tok_test", "VISA", "0002", "12/99")));
  }

  private String validOrder(int quantity) {
    return "{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"Street\","
        + "\"deliveryCity\":\"City\",\"deliveryState\":\"ST\","
        + "\"deliveryZip\":\"00000\",\"paymentMethodId\":\"PAYMENT-ID\","
        + "\"items\":[{\"taco\":{\"name\":\"Valid taco\","
        + "\"ingredientIds\":[\"WRAP\",\"SLSA\"]},\"quantity\":"
        + quantity + "}]}";
  }

  private Ingredient ingredient(String id, String price) {
    return new Ingredient(id, id + " ingredient",
        "WRAP".equals(id) ? Ingredient.Type.WRAP : Ingredient.Type.SAUCE,
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
