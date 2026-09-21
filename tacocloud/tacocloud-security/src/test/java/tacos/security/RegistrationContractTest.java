package tacos.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.User;

class RegistrationContractTest {

  @Test
  void shouldUseDelegatingAdaptivePasswordHash() {
    PasswordEncoder encoder = new SecurityConfig().encoder();

    String encoded = encoder.encode("correct horse battery staple");

    assertNotEquals("correct horse battery staple", encoded);
    assertTrue(encoded.startsWith("{bcrypt}"));
    assertTrue(encoder.matches("correct horse battery staple", encoded));
  }

  @Test
  void shouldReturnPublisherFromRegistrationPost() throws Exception {
    Method method = RegistrationController.class
        .getDeclaredMethod("processRegistration", RegistrationForm.class);

    assertTrue(Publisher.class.isAssignableFrom(method.getReturnType()));
  }

  @Test
  void shouldKeepPasswordHashOutOfJsonAndToString() throws Exception {
    User user = new User("alice", "{bcrypt}synthetic-hash", "Alice", "Street",
        "City", "ST", "00000", "0000000000", "alice@example.test");

    String json = new ObjectMapper().writeValueAsString(user);

    assertFalse(json.contains("password"));
    assertFalse(json.contains("synthetic-hash"));
    assertFalse(user.toString().contains("synthetic-hash"));
  }

  @Test
  void shouldKeepRawPasswordOutOfRegistrationFormToString() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("alice");
    form.setPassword("correct horse battery staple");

    assertFalse(form.toString().contains("correct horse battery staple"));
  }
}
