package tacos.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

class FakePaymentGatewayTest {

  @Test
  void shouldCreateOpaqueTokenAndKeepOnlySafeCardSummary() {
    String syntheticPan = "4000000000000002";
    PaymentTokenizationCommand command =
        new PaymentTokenizationCommand(syntheticPan, "12/99", "123");

    StepVerifier.create(new FakePaymentGateway().tokenize(command))
        .assertNext(payment -> {
          assertNotEquals(syntheticPan, payment.getToken());
          assertFalse(payment.getToken().contains(syntheticPan));
          assertFalse(payment.toString().contains(payment.getToken()));
          assertTrue(payment.getToken().startsWith("tok_fake_"));
          assertEquals("VISA", payment.getBrand());
          assertEquals("0002", payment.getLast4());
          assertEquals("12/99", payment.getExpiration());
        })
        .verifyComplete();

    assertFalse(command.toString().contains(syntheticPan));
    assertFalse(command.toString().contains("123"));
  }
}
