package tacos.security;

import javax.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import reactor.core.publisher.Mono;

@Controller
@RequestMapping("/register")
public class RegistrationController {

  private final RegistrationService registrationService;

  public RegistrationController(RegistrationService registrationService) {
    this.registrationService = registrationService;
  }
  
  @GetMapping
  public String registerForm() {
    return "registration";
  }
  
  @PostMapping
  public Mono<String> processRegistration(@Valid RegistrationForm form) {
    return registrationService.register(form)
        .thenReturn("redirect:/login");
  }

}
