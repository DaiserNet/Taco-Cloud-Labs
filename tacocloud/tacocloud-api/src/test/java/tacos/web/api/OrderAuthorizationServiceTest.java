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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.payment.PaymentMethodService;
import tacos.pricing.OrderPricingService;

class OrderAuthorizationServiceTest {

  private OrderRepository orderRepo;
  private UserRepository userRepo;
  private EmailOrderService emailOrderService;
  private OrderMessagingService messaging;
  private PaymentMethodService paymentMethodService;
  private OrderPricingService orderPricingService;
  private OrderService service;

  @BeforeEach
  void setUp() {
    orderRepo = mock(OrderRepository.class);
    userRepo = mock(UserRepository.class);
    emailOrderService = mock(EmailOrderService.class);
    messaging = mock(OrderMessagingService.class);
    paymentMethodService = mock(PaymentMethodService.class);
    orderPricingService = mock(OrderPricingService.class);
    service = new OrderService(orderRepo, emailOrderService, messaging,
        userRepo, mock(Validator.class), paymentMethodService,
        orderPricingService);
  }

  @Test
  void shouldListOnlyAuthenticatedUsersOrders() {
    TacoOrder own = order("OWN", "alice");
    when(orderRepo.findByUserUsernameOrderByPlacedAtDesc("alice"))
        .thenReturn(Flux.just(own));

    StepVerifier.create(service.findVisibleOrders(user("alice")))
        .expectNext(own)
        .verifyComplete();

    verify(orderRepo).findByUserUsernameOrderByPlacedAtDesc("alice");
    verify(orderRepo, never()).findAll();
  }

  @Test
  void shouldAllowAdminToAuditAllOrders() {
    TacoOrder first = order("ONE", "alice");
    TacoOrder second = order("TWO", "bob");
    when(orderRepo.findAll()).thenReturn(Flux.just(first, second));

    StepVerifier.create(service.findVisibleOrders(admin("auditor")))
        .expectNext(first, second)
        .verifyComplete();

    verify(orderRepo).findAll();
    verify(orderRepo, never()).findByUserUsernameOrderByPlacedAtDesc(any());
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

    InOrder effects = inOrder(messaging, orderRepo);
    effects.verify(messaging).sendOrder(requested);
    effects.verify(orderRepo).save(requested);
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
