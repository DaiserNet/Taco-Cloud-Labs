package tacos.web.api;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.PaymentMethodResponse;
import tacos.api.dto.PaymentTokenizeRequest;
import tacos.payment.PaymentMethodService;
import tacos.payment.PaymentTokenizationCommand;

@RestController
@RequestMapping(path = "/api/payment-methods", produces = "application/json")
public class PaymentMethodController {

  private final PaymentMethodService paymentMethodService;

  public PaymentMethodController(PaymentMethodService paymentMethodService) {
    this.paymentMethodService = paymentMethodService;
  }

  @PostMapping(path = "/tokenize", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<PaymentMethodResponse> tokenize(
      @Valid @RequestBody PaymentTokenizeRequest request,
      Authentication authentication) {
    PaymentTokenizationCommand command = new PaymentTokenizationCommand(
        request.getPan(), request.getExpiration(), request.getCvv());
    return paymentMethodService.tokenize(command, authentication)
        .map(payment -> new PaymentMethodResponse(
            payment.getId(), payment.getBrand(), payment.getLast4()));
  }
}
