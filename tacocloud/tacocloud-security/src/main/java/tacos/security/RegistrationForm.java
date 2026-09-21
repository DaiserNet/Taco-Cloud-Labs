package tacos.security;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;
import tacos.User;

@Data
public class RegistrationForm {

  @NotBlank(message = "must not be blank")
  @Size(max = 64, message = "must contain at most 64 characters")
  private String username;

  @NotBlank(message = "must not be blank")
  @Size(max = 100, message = "must contain at most 100 characters")
  @ToString.Exclude
  private String password;

  private String fullname;
  private String street;
  private String city;
  private String state;
  private String zip;
  private String phone;

  @NotBlank(message = "must not be blank")
  @Email(message = "must be a valid email address")
  @Size(max = 254, message = "must contain at most 254 characters")
  private String email;
  
  public User toUser(PasswordEncoder passwordEncoder) {
    return new User(
        username, passwordEncoder.encode(password), 
        fullname, street, city, state, zip, phone, email);
  }
  
}
