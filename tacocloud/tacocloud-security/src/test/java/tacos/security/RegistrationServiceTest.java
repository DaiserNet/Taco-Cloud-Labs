package tacos.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import tacos.User;
import tacos.data.UserIndexInitializer;
import tacos.data.UserRepository;

class RegistrationServiceTest {

  private UserRepository userRepo;
  private PasswordEncoder encoder;
  private UserIndexInitializer userIndexInitializer;
  private RegistrationService service;

  @BeforeEach
  void setUp() {
    userRepo = mock(UserRepository.class);
    encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    userIndexInitializer = mock(UserIndexInitializer.class);
    when(userIndexInitializer.ensureUniqueIndexes()).thenReturn(Mono.empty());
    service = new RegistrationService(userRepo, encoder, userIndexInitializer);
  }

  @Test
  void shouldSubscribeToSaveAndPersistOnlyTheAdaptiveHash() {
    RegistrationForm form = form("alice", "alice@example.test");
    User persisted = new User("alice", "stored-hash", "Alice", "Street", "City",
        "ST", "00000", "0000000000", "alice@example.test");
    PublisherProbe<User> saveProbe = PublisherProbe.of(Mono.just(persisted));
    when(userRepo.findByUsername("alice")).thenReturn(Mono.empty());
    when(userRepo.findByEmail("alice@example.test")).thenReturn(Mono.empty());
    when(userRepo.save(any(User.class))).thenReturn(saveProbe.mono());

    Mono<User> registration = service.register(form);
    assertTrue(!saveProbe.wasSubscribed());

    StepVerifier.create(registration)
        .expectNext(persisted)
        .verifyComplete();

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(userRepo).save(saved.capture());
    assertNotEquals(form.getPassword(), saved.getValue().getPassword());
    assertTrue(saved.getValue().getPassword().startsWith("{bcrypt}"));
    assertTrue(encoder.matches(form.getPassword(), saved.getValue().getPassword()));
    saveProbe.assertWasSubscribed();
  }

  @Test
  void shouldRejectExistingUsernameBeforeSave() {
    RegistrationForm form = form("alice", "new@example.test");
    when(userRepo.findByUsername("alice"))
        .thenReturn(Mono.just(user("alice", "old@example.test")));
    when(userRepo.findByEmail("new@example.test")).thenReturn(Mono.empty());

    StepVerifier.create(service.register(form))
        .expectError(RegistrationConflictException.class)
        .verify();

    verify(userRepo, never()).save(any());
  }

  @Test
  void shouldRejectExistingEmailBeforeSave() {
    RegistrationForm form = form("new-name", "alice@example.test");
    when(userRepo.findByUsername("new-name")).thenReturn(Mono.empty());
    when(userRepo.findByEmail("alice@example.test"))
        .thenReturn(Mono.just(user("alice", "alice@example.test")));

    StepVerifier.create(service.register(form))
        .expectError(RegistrationConflictException.class)
        .verify();

    verify(userRepo, never()).save(any());
  }

  @Test
  void shouldMapDuplicateKeyRaceToRegistrationConflict() {
    RegistrationForm form = form("alice", "alice@example.test");
    when(userRepo.findByUsername("alice")).thenReturn(Mono.empty());
    when(userRepo.findByEmail("alice@example.test")).thenReturn(Mono.empty());
    when(userRepo.save(any(User.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("simulated unique-index race")));

    StepVerifier.create(service.register(form))
        .expectError(RegistrationConflictException.class)
        .verify();

    verify(userRepo).save(any(User.class));
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
    return new User(username, "{bcrypt}existing", "Alice", "Street", "City",
        "ST", "00000", "0000000000", email);
  }
}
