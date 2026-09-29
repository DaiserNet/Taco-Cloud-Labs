package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Collections;

import javax.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import reactor.test.publisher.TestPublisher;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.pricing.CouponService;
import tacos.pricing.OrderPricingService;

class OrderFromEmailControllerTest {

  private OrderRepository repo;
  private OrderMessagingService messaging;
  private EmailOrderService emailOrderService;
  private UserRepository userRepo;
  private OrderPricingService orderPricingService;
  private CouponService couponService;
  private InventoryService inventoryService;
  private OrderApiController controller;

  @BeforeEach
  void setUp() {
    repo = mock(OrderRepository.class);
    messaging = mock(OrderMessagingService.class);
    emailOrderService = mock(EmailOrderService.class);
    userRepo = mock(UserRepository.class);
    orderPricingService = mock(OrderPricingService.class);
    couponService = mock(CouponService.class);
    inventoryService = mock(InventoryService.class);
    InventoryReservation reservation = mock(InventoryReservation.class);
    when(reservation.getId()).thenReturn("RESERVATION-ID");
    when(inventoryService.reserve(any(TacoOrder.class)))
        .thenReturn(Mono.just(reservation));
    when(inventoryService.accept(any(String.class), any(String.class)))
        .thenReturn(Mono.empty());
    when(inventoryService.release(any(String.class))).thenReturn(Mono.empty());
    when(orderPricingService.price(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(couponService.apply(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    OrderService orderService = new OrderService(
        repo, emailOrderService, messaging, userRepo, mock(Validator.class),
        mock(tacos.payment.PaymentMethodService.class), orderPricingService,
        couponService, inventoryService);
    controller = new OrderApiController(orderService, new OrderMapper());
  }

  @Test
  void shouldSubscribeToColdConversionOnceAndPublishSavedOrderOnce() {
    TacoOrder converted = convertedOrder();
    TacoOrder saved = new TacoOrder();
    saved.setId("ORDER-ID");
    PublisherProbe<TacoOrder> conversion = PublisherProbe.of(Mono.just(converted));
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(conversion.mono());
    when(repo.save(converted)).thenReturn(Mono.just(saved));

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder(), user()))
        .assertNext(order -> assertEquals("ORDER-ID", order.getId()))
        .verifyComplete();

    assertEquals(tacos.OrderStatus.CREATED, converted.getStatus());
    assertEquals("EMAIL", converted.getStatusHistory().get(0).getOrigin());

    assertEquals(1, conversion.subscribeCount());
    verify(repo, times(1)).save(converted);
    verify(messaging, times(1)).sendOrder(saved);
    InOrder interactions = inOrder(repo, messaging);
    interactions.verify(repo).save(converted);
    interactions.verify(messaging).sendOrder(saved);
  }

  @Test
  void shouldNotSaveOrPublishWhenConversionFails() {
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.error(new InvalidEmailOrderException("invalid email order")));

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder(), user()))
        .expectError(InvalidEmailOrderException.class)
        .verify();

    verify(repo, never()).save(any(TacoOrder.class));
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldNotPublishWhenSaveFails() {
    TacoOrder converted = convertedOrder();
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(converted));
    when(repo.save(converted))
        .thenReturn(Mono.error(new IllegalStateException("save failed")));

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder(), user()))
        .expectErrorMatches(error -> error instanceof IllegalStateException
            && "save failed".equals(error.getMessage()))
        .verify();

    verifyNoInteractions(messaging);
    verify(inventoryService).release("RESERVATION-ID");
  }

  @Test
  void shouldWaitForSaveBeforePublishing() {
    TacoOrder converted = convertedOrder();
    TacoOrder saved = new TacoOrder();
    saved.setId("ORDER-ID");
    TestPublisher<TacoOrder> save = TestPublisher.createCold();
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(converted));
    when(repo.save(converted)).thenReturn(save.mono());

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder(), user()))
        .expectSubscription()
        .then(() -> verifyNoInteractions(messaging))
        .expectNoEvent(Duration.ofMillis(20))
        .then(() -> save.emit(saved))
        .assertNext(order -> assertEquals("ORDER-ID", order.getId()))
        .verifyComplete();

    verify(messaging).sendOrder(saved);
  }

  @Test
  void shouldPropagatePublishFailureInsteadOfCompletingSuccessfully() {
    TacoOrder converted = convertedOrder();
    TacoOrder saved = new TacoOrder();
    saved.setId("ORDER-ID");
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(converted));
    when(repo.save(converted)).thenReturn(Mono.just(saved));
    doThrow(new IllegalStateException("send failed"))
        .when(messaging).sendOrder(saved);

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder(), user()))
        .expectErrorMatches(error -> error instanceof IllegalStateException
            && "send failed".equals(error.getMessage()))
        .verify();

    verify(repo).save(converted);
    verify(messaging).sendOrder(saved);
  }

  @Test
  void shouldReturnCreatedWithThePersistedOrder() throws Exception {
    TacoOrder converted = convertedOrder();
    TacoOrder saved = new TacoOrder();
    saved.setId("ORDER-ID");
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(converted));
    when(repo.save(converted)).thenReturn(Mono.just(saved));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    MvcResult result = mvc.perform(post("/api/orders/fromEmail")
            .principal(user())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"owner@example.test\",\"tacos\":["
                + "{\"name\":\"Valid taco\",\"ingredients\":[\"WRAP\"]}]}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    mvc.perform(asyncDispatch(result))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("ORDER-ID"));
    verify(messaging).sendOrder(saved);
  }

  private TacoOrder convertedOrder() {
    TacoOrder order = new TacoOrder();
    order.setUser(new User("alice", "N/A", "Alice", "Street", "City", "ST",
        "00000", "0000000000", "alice@example.test"));
    return order;
  }

  private Authentication user() {
    return new UsernamePasswordAuthenticationToken("alice", "N/A",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
