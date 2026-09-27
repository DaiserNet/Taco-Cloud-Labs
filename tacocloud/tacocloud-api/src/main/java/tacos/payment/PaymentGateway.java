package tacos.payment;

import reactor.core.publisher.Mono;

public interface PaymentGateway {
  Mono<TokenizedPayment> tokenize(PaymentTokenizationCommand payment);
}
