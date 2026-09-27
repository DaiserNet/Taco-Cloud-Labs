package tacos.payment;

import java.util.UUID;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

@Service
public class FakePaymentGateway implements PaymentGateway {

  @Override
  public Mono<TokenizedPayment> tokenize(PaymentTokenizationCommand payment) {
    return Mono.fromSupplier(() -> new TokenizedPayment(
        "tok_fake_" + UUID.randomUUID().toString().replace("-", ""),
        brandFor(payment.getPan()),
        payment.getPan().substring(payment.getPan().length() - 4),
        payment.getExpiration()));
  }

  private String brandFor(String pan) {
    if (pan.startsWith("4")) {
      return "VISA";
    }
    if (pan.startsWith("5")) {
      return "MASTERCARD";
    }
    return "OTHER";
  }
}
