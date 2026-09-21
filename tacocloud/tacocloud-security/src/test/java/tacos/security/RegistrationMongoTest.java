package tacos.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.User;
import tacos.data.UserIndexInitializer;
import tacos.data.UserRepository;

@SpringBootTest(
    classes = RegistrationMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.config.name=tc10-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc10-test",
        "spring.mongodb.embedded.version=3.5.5"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrationMongoTest {

  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = UserRepository.class)
  @Import({RegistrationService.class, UserIndexInitializer.class})
  static class TestApplication {
    @Bean
    PasswordEncoder passwordEncoder() {
      return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
  }

  @Autowired
  private UserRepository userRepo;

  @Autowired
  private RegistrationService registrationService;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private UserIndexInitializer userIndexInitializer;

  @BeforeEach
  void setUp() {
    StepVerifier.create(userIndexInitializer.ensureUniqueIndexes()
        .then(userRepo.deleteAll()))
        .expectComplete()
        .verify(TEST_TIMEOUT);
  }

  @Test
  void shouldPersistHashAndReloadUserForAuthentication() {
    RegistrationForm form = form("alice", "alice@example.test");

    Mono<User> registeredAndReloaded = registrationService.register(form)
        .flatMap(saved -> userRepo.findByUsername(saved.getUsername()));

    StepVerifier.create(registeredAndReloaded)
        .assertNext(user -> {
          assertNotNull(user.getId());
          assertNotEquals(form.getPassword(), user.getPassword());
          assertTrue(user.getPassword().startsWith("{bcrypt}"));
          assertTrue(passwordEncoder.matches(form.getPassword(), user.getPassword()));
        })
        .expectComplete()
        .verify(TEST_TIMEOUT);
  }

  @Test
  void shouldEnforceUniqueUsernameAndEmailIndexes() {
    User original = user("alice", "alice@example.test");
    User sameUsername = user("alice", "other@example.test");
    User sameEmail = user("other", "alice@example.test");

    StepVerifier.create(userRepo.save(original))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(userRepo.save(sameUsername))
        .expectError(DuplicateKeyException.class)
        .verify(TEST_TIMEOUT);
    StepVerifier.create(userRepo.save(sameEmail))
        .expectError(DuplicateKeyException.class)
        .verify(TEST_TIMEOUT);
  }

  private RegistrationForm form(String username, String email) {
    RegistrationForm form = new RegistrationForm();
    form.setUsername(username);
    form.setPassword("correct horse battery staple");
    form.setFullname("Alice");
    form.setStreet("Street");
    form.setCity("City");
    form.setState("ST");
    form.setZip("00000");
    form.setPhone("0000000000");
    form.setEmail(email);
    return form;
  }

  private User user(String username, String email) {
    return new User(username, "{bcrypt}synthetic", "Alice", "Street", "City",
        "ST", "00000", "0000000000", email);
  }
}
