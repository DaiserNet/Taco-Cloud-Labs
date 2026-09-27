package tacos.payment;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.PaymentMethod;
import tacos.User;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;

@Service
public class PaymentMethodService {

  private final UserRepository userRepo;
  private final PaymentMethodRepository paymentMethodRepo;
  private final PaymentGateway paymentGateway;

  public PaymentMethodService(UserRepository userRepo,
      PaymentMethodRepository paymentMethodRepo, PaymentGateway paymentGateway) {
    this.userRepo = userRepo;
    this.paymentMethodRepo = paymentMethodRepo;
    this.paymentGateway = paymentGateway;
  }

  public Mono<PaymentMethod> tokenize(
      PaymentTokenizationCommand command, Authentication authentication) {
    return currentUser(authentication)
        .flatMap(user -> paymentGateway.tokenize(command)
            .map(payment -> new PaymentMethod(user, payment.getToken(),
                payment.getBrand(), payment.getLast4(), payment.getExpiration())))
        .flatMap(paymentMethodRepo::save);
  }

  public Mono<PaymentMethod> findOwned(
      String paymentMethodId, Authentication authentication) {
    return Mono.defer(() -> {
      if (!isAuthenticated(authentication)) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      if (!hasUserRole(authentication)) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
      }
      return paymentMethodRepo
          .findByIdAndUserUsername(paymentMethodId, authentication.getName())
          .switchIfEmpty(Mono.error(
              new ResponseStatusException(HttpStatus.NOT_FOUND)));
    });
  }

  private Mono<User> currentUser(Authentication authentication) {
    return Mono.defer(() -> {
      if (!isAuthenticated(authentication)) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      if (!hasUserRole(authentication)) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
      }
      return userRepo.findByUsername(authentication.getName())
          .switchIfEmpty(Mono.error(
              new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    });
  }

  private boolean isAuthenticated(Authentication authentication) {
    return authentication != null && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken);
  }

  private boolean hasUserRole(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_USER".equals(authority.getAuthority()));
  }
}
