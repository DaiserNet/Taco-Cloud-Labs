package tacos.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT, reason = "Username or email already registered")
public class RegistrationConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RegistrationConflictException() {
    super("Username or email already registered");
  }

  public RegistrationConflictException(Throwable cause) {
    super("Username or email already registered", cause);
  }
}
