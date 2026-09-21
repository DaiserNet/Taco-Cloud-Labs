package tacos.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.User;
import tacos.data.UserIndexInitializer;
import tacos.data.UserRepository;

@Service
public class RegistrationService {

  private final UserRepository userRepo;
  private final PasswordEncoder passwordEncoder;
  private final UserIndexInitializer userIndexInitializer;

  public RegistrationService(UserRepository userRepo, PasswordEncoder passwordEncoder,
      UserIndexInitializer userIndexInitializer) {
    this.userRepo = userRepo;
    this.passwordEncoder = passwordEncoder;
    this.userIndexInitializer = userIndexInitializer;
  }

  public Mono<User> register(RegistrationForm form) {
    return Mono.defer(() -> userIndexInitializer.ensureUniqueIndexes()
        .then(Mono.zip(
            userRepo.findByUsername(form.getUsername()).hasElement(),
            userRepo.findByEmail(form.getEmail()).hasElement())))
        .flatMap(existing -> {
          if (existing.getT1() || existing.getT2()) {
            return Mono.error(new RegistrationConflictException());
          }
          return Mono.defer(() -> userRepo.save(form.toUser(passwordEncoder)));
        })
        .onErrorMap(DuplicateKeyException.class,
            error -> new RegistrationConflictException(error));
  }
}
