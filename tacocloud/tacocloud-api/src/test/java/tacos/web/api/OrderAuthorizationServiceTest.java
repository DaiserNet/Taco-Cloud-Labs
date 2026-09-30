package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import javax.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryService;
import tacos.payment.PaymentMethodService;
import tacos.pricing.CouponService;
import tacos.pricing.OrderPricingService;

class OrderAuthorizationServiceTest {

  private OrderRepository orderRepo;
  private UserRepository userRepo;
  private EmailOrderService emailOrderService;
  private OrderMessagingService messaging;
  private PaymentMethodService paymentMethodService;
  private OrderPricingService orderPricingService;
  private CouponService couponService;
  private InventoryService inventoryService;
  private InventoryReservation inventoryReservation;
  private OrderService service;

  @BeforeEach
  void setUp() {
    orderRepo = mock(OrderRepository.class);
    userRepo = mock(UserRepository.class);
    emailOrderService = mock(EmailOrderService.class);
    messaging = mock(OrderMessagingService.class);
    paymentMethodService = mock(PaymentMethodService.class);
    orderPricingService = mock(OrderPricingService.class);
    couponService = mock(CouponService.class);
    inventoryService = mock(InventoryService.class);
    inventoryReservation = mock(InventoryReservation.class);
    when(inventoryReservation.getId()).thenReturn("RESERVATION-ID");
    when(inventoryService.reserve(any(TacoOrder.class)))
        .thenReturn(Mono.just(inventoryReservation));
    when(inventoryService.accept(any(String.class), any(String.class)))
        .thenReturn(Mono.empty());
    service = new OrderService(orderRepo, emailOrderService,
        OrderOutboxTestSupport.commitUsing(orderRepo, inventoryService),
        userRepo, mock(Validator.class), paymentMethodService,
        orderPricingService, couponService, inventoryService,
        new tacos.observability.OrderMetrics(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), "noop"));
  }

  @Test
  void shouldReplaceClientOwnerWithAuthenticatedUserBeforeCreate() {
    User authenticated = userDomain("alice");
    TacoOrder requested = order("CLIENT-ID", "attacker");
    requested.setPaymentMethodId("PAYMENT-ID");
    PaymentMethod payment = new PaymentMethod(
        authenticated, "tok_test", "VISA", "0002", "12/99");
    when(userRepo.findByUsername("alice")).thenReturn(Mono.just(authenticated));
    when(paymentMethodService.findOwned("PAYMENT-ID", user("alice")))
        .thenReturn(Mono.just(payment));
    when(orderPricingService.price(requested)).thenReturn(Mono.just(requested));
    when(couponService.apply(requested)).thenReturn(Mono.just(requested));
    when(orderRepo.save(requested)).thenReturn(Mono.just(requested));

    StepVerifier.create(service.createOrder(requested, user("alice")))
        .assertNext(saved -> {
          assertSame(authenticated, saved.getUser());
          org.junit.jupiter.api.Assertions.assertEquals(
              "VISA", saved.getPaymentBrand());
          org.junit.jupiter.api.Assertions.assertEquals(
              "0002", saved.getPaymentLast4());
        })
        .verifyComplete();

    verify(orderRepo).save(requested);
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldForbidEmailOrderOwnedByDifferentUserBeforeEffects() {
    TacoOrder converted = order("EMAIL", "alice");
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(converted));

    StepVerifier.create(service.createFromEmail(new EmailOrder(), user("bob")))
        .expectErrorSatisfies(error -> {
          org.junit.jupiter.api.Assertions.assertTrue(
              error instanceof ResponseStatusException);
          org.junit.jupiter.api.Assertions.assertEquals(403,
              ((ResponseStatusException) error).getRawStatusCode());
        })
        .verify();

    verify(orderRepo, never()).save(any());
    verifyNoInteractions(messaging);
  }

  private TacoOrder order(String id, String ownerName) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUser(userDomain(ownerName));
    return order;
  }

  private User userDomain(String username) {
    User user = new User(username, "N/A", "Owner", "Street", "City", "ST",
        "00000", "0000000000", username + "@example.test");
    user.setId(username + "-id");
    return user;
  }

  private Authentication user(String username) {
    return authentication(username, "ROLE_USER");
  }

  private Authentication admin(String username) {
    return authentication(username, "ROLE_ADMIN");
  }

  private Authentication authentication(String username, String... roles) {
    return new UsernamePasswordAuthenticationToken(username, "N/A",
        Arrays.stream(roles).map(SimpleGrantedAuthority::new)
            .collect(java.util.stream.Collectors.toList()));
  }
}
