package tacos.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import tacos.PaymentMethod;
import tacos.User;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;

class PaymentMethodServiceTest {

  private UserRepository userRepo;
  private PaymentMethodRepository paymentRepo;
  private PaymentGateway gateway;
  private PaymentMethodService service;

  @BeforeEach
  void setUp() {
    userRepo = mock(UserRepository.class);
    paymentRepo = mock(PaymentMethodRepository.class);
    gateway = mock(PaymentGateway.class);
    service = new PaymentMethodService(userRepo, paymentRepo, gateway);
  }

  @Test
  void shouldSubscribeToGatewayAndPersistOnlyTokenizedDataForCurrentUser() {
    User owner = user("alice");
    PaymentTokenizationCommand command = new PaymentTokenizationCommand(
        "4000000000000002", "12/99", "123");
    TokenizedPayment tokenized =
        new TokenizedPayment("tok_test", "VISA", "0002", "12/99");
    PublisherProbe<TokenizedPayment> gatewayResult =
        PublisherProbe.of(Mono.just(tokenized));
    when(userRepo.findByUsername("alice")).thenReturn(Mono.just(owner));
    when(gateway.tokenize(command)).thenReturn(gatewayResult.mono());
    when(paymentRepo.save(any(PaymentMethod.class))).thenAnswer(invocation -> {
      PaymentMethod method = invocation.getArgument(0);
      method.setId("PAYMENT-ID");
      return Mono.just(method);
    });

    StepVerifier.create(service.tokenize(command, userAuthentication("alice")))
        .assertNext(saved -> {
          assertEquals("PAYMENT-ID", saved.getId());
          assertSame(owner, saved.getUser());
          assertEquals("tok_test", saved.getPaymentToken());
          assertEquals("VISA", saved.getBrand());
          assertEquals("0002", saved.getLast4());
          assertFalse(saved.toString().contains("tok_test"));
          assertFalse(saved.toString().contains("4000000000000002"));
          assertFalse(saved.toString().contains("123"));
        })
        .verifyComplete();

    gatewayResult.assertWasSubscribed();
    ArgumentCaptor<PaymentMethod> persisted =
        ArgumentCaptor.forClass(PaymentMethod.class);
    verify(paymentRepo).save(persisted.capture());
    assertEquals("tok_test", persisted.getValue().getPaymentToken());
  }

  @Test
  void shouldResolveOnlyPaymentMethodOwnedByAuthenticatedUser() {
    PaymentMethod method = new PaymentMethod(
        user("alice"), "tok_test", "VISA", "0002", "12/99");
    when(paymentRepo.findByIdAndUserUsername("PAYMENT-ID", "alice"))
        .thenReturn(Mono.just(method));

    StepVerifier.create(
        service.findOwned("PAYMENT-ID", userAuthentication("alice")))
        .expectNext(method)
        .verifyComplete();

    verify(paymentRepo).findByIdAndUserUsername("PAYMENT-ID", "alice");
  }

  private User user(String username) {
    User user = new User(username, "N/A", "Owner", "Street", "City", "ST",
        "00000", "0000000000", username + "@example.test");
    user.setId(username + "-id");
    return user;
  }

  private Authentication userAuthentication(String username) {
    return new UsernamePasswordAuthenticationToken(username, "N/A",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
