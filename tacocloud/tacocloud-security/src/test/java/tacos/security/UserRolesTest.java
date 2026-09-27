package tacos.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import tacos.User;

class UserRolesTest {

  @Test
  void shouldGiveNewRegistrationOnlyUserRole() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("alice");
    form.setPassword("correct horse battery staple");
    form.setEmail("alice@example.test");

    User user = form.toUser(PasswordEncoderFactories.createDelegatingPasswordEncoder());

    assertEquals(new LinkedHashSet<>(Arrays.asList("ROLE_USER")), authorities(user));
  }

  @Test
  void shouldExposePersistedAdminAndKitchenRoles() {
    User user = new User("operator", "{bcrypt}hash", "Operator", "Street", "City",
        "ST", "00000", "0000000000", "operator@example.test",
        new LinkedHashSet<>(Arrays.asList("ADMIN", "KITCHEN")));

    assertEquals(new LinkedHashSet<>(Arrays.asList("ROLE_ADMIN", "ROLE_KITCHEN")),
        authorities(user));
  }

  private Set<String> authorities(User user) {
    return user.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
