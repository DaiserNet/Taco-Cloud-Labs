package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.util.Collections;

import javax.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.error.ApiExceptionHandler;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.inventory.InsufficientStockException;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.messaging.OrderMessagingService;
import tacos.payment.PaymentMethodService;
import tacos.pricing.CouponService;
import tacos.pricing.OrderPricingService;

class OrderInventoryControllerTest {

  private OrderRepository orderRepo;
  private UserRepository userRepo;
  private PaymentMethodService paymentMethodService;
  private OrderPricingService pricingService;
  private CouponService couponService;
  private InventoryService inventoryService;
  private OrderMessagingService messaging;
  private InventoryReservation reservation;
  private OrderService orderService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    orderRepo = mock(OrderRepository.class);
    userRepo = mock(UserRepository.class);
    paymentMethodService = mock(PaymentMethodService.class);
    pricingService = mock(OrderPricingService.class);
    couponService = mock(CouponService.class);
    inventoryService = mock(InventoryService.class);
    messaging = mock(OrderMessagingService.class);
    reservation = mock(InventoryReservation.class);

    when(reservation.getId()).thenReturn("RESERVATION-ID");
    when(pricingService.price(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(couponService.apply(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(inventoryService.accept(any(String.class), any(String.class)))
        .thenReturn(Mono.empty());
    when(inventoryService.release(any(String.class))).thenReturn(Mono.empty());

    orderService = new OrderService(orderRepo, mock(EmailOrderService.class),
        messaging, userRepo, mock(Validator.class), paymentMethodService,
        pricingService, couponService, inventoryService);
    mvc = MockMvcBuilders.standaloneSetup(
        new OrderApiController(orderService, new OrderMapper()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void shouldReserveSaveAcceptAndPublishInOrder() {
    TacoOrder order = requestedOrder();
    prepareOwnerAndPayment();
    when(inventoryService.reserve(order)).thenReturn(Mono.just(reservation));
    when(orderRepo.save(order)).thenAnswer(invocation -> {
      order.setId("ORDER-ID");
      return Mono.just(order);
    });

    StepVerifier.create(orderService.createOrder(order, user()))
        .assertNext(saved -> assertEquals("ORDER-ID", saved.getId()))
        .verifyComplete();

    InOrder effects = inOrder(inventoryService, orderRepo, messaging);
    effects.verify(inventoryService).reserve(order);
    effects.verify(orderRepo).save(order);
    effects.verify(inventoryService).accept("RESERVATION-ID", "ORDER-ID");
    effects.verify(messaging).sendOrder(order);
  }

  @Test
  void shouldRejectChangedQuoteBeforeInventoryOrPersistence() {
    TacoOrder order = requestedOrder();
    prepareOwnerAndPayment();

    StepVerifier.create(orderService.createOrder(order, user(), priced ->
        Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
            "Quote changed."))))
        .expectErrorMatches(error -> error instanceof ResponseStatusException
            && ((ResponseStatusException) error).getStatus()
                == HttpStatus.CONFLICT)
        .verify();

    verify(pricingService).price(order);
    verify(couponService).apply(order);
    verifyNoInteractions(inventoryService, orderRepo, messaging);
  }

  @Test
  void shouldReleaseReservationWhenOrderSaveFails() {
    TacoOrder order = requestedOrder();
    prepareOwnerAndPayment();
    when(inventoryService.reserve(order)).thenReturn(Mono.just(reservation));
    when(orderRepo.save(order))
        .thenReturn(Mono.error(new IllegalStateException("save failed")));

    StepVerifier.create(orderService.createOrder(order, user()))
        .expectErrorMatches(error -> error instanceof IllegalStateException
            && "save failed".equals(error.getMessage()))
        .verify();

    verify(inventoryService).release("RESERVATION-ID");
    verify(inventoryService, never()).accept(any(String.class), any(String.class));
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldDeleteOrderAndReleaseReservationWhenAcceptanceFails() {
    TacoOrder order = requestedOrder();
    prepareOwnerAndPayment();
    when(inventoryService.reserve(order)).thenReturn(Mono.just(reservation));
    when(orderRepo.save(order)).thenAnswer(invocation -> {
      order.setId("ORDER-ID");
      return Mono.just(order);
    });
    when(inventoryService.accept("RESERVATION-ID", "ORDER-ID"))
        .thenReturn(Mono.error(new IllegalStateException("accept failed")));
    when(orderRepo.deleteById("ORDER-ID")).thenReturn(Mono.empty());

    StepVerifier.create(orderService.createOrder(order, user()))
        .expectErrorMatches(error -> error instanceof IllegalStateException
            && "accept failed".equals(error.getMessage()))
        .verify();

    InOrder compensation = inOrder(inventoryService, orderRepo);
    compensation.verify(inventoryService).reserve(order);
    compensation.verify(orderRepo).save(order);
    compensation.verify(inventoryService)
        .accept("RESERVATION-ID", "ORDER-ID");
    compensation.verify(orderRepo).deleteById("ORDER-ID");
    compensation.verify(inventoryService).release("RESERVATION-ID");
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldReturnConflictWhenStockIsInsufficientWithoutCreatingOrder()
      throws Exception {
    prepareOwnerAndPayment();
    when(inventoryService.reserve(any(TacoOrder.class)))
        .thenReturn(Mono.error(new InsufficientStockException("SLSA", 2)));

    perform(post("/api/orders").content(validOrder()), user())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
        .andExpect(jsonPath("$.detail")
            .value("Insufficient stock for ingredient: SLSA."));

    verify(orderRepo, never()).save(any(TacoOrder.class));
    verifyNoInteractions(messaging);
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

  private TacoOrder requestedOrder() {
    TacoOrder order = new TacoOrder();
    order.setPaymentMethodId("PAYMENT-ID");
    return order;
  }

  private String validOrder() {
    return "{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"Street\"," 
        + "\"deliveryCity\":\"City\",\"deliveryState\":\"ST\"," 
        + "\"deliveryZip\":\"00000\",\"paymentMethodId\":\"PAYMENT-ID\"," 
        + "\"items\":[{\"taco\":{\"name\":\"Valid taco\"," 
        + "\"ingredientIds\":[\"WRAP\",\"SLSA\"]},\"quantity\":2}]}";
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
