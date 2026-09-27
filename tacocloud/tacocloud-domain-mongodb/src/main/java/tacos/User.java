package tacos;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.
                                          SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import lombok.Data;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.ToString;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@NoArgsConstructor(access=AccessLevel.PRIVATE, force=true)
@Document
public class User implements UserDetails {

  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  
  private final String username;
  
  @JsonIgnore
  @ToString.Exclude
  private final String password;
  private final String fullname;
  private final String street;
  private final String city;
  private final String state;
  private final String zip;
  private final String phoneNumber;
  private final String email;

  private final Set<String> roles;

  public User(String username, String password, String fullname, String street,
              String city, String state, String zip, String phoneNumber,
              String email) {
    this(username, password, fullname, street, city, state, zip, phoneNumber,
        email, Collections.singleton("USER"));
  }

  @PersistenceConstructor
  public User(String username, String password, String fullname, String street,
              String city, String state, String zip, String phoneNumber,
              String email, Set<String> roles) {
    this.username = username;
    this.password = password;
    this.fullname = fullname;
    this.street = street;
    this.city = city;
    this.state = state;
    this.zip = zip;
    this.phoneNumber = phoneNumber;
    this.email = email;
    this.roles = roles == null || roles.isEmpty()
        ? Collections.singleton("USER")
        : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return roles.stream()
        .map(role -> role.toUpperCase(Locale.ROOT))
        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toList());
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

}
