package tacos.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tacos.User;

class RegistrationControllerTest {

  private RegistrationService registrationService;
  private RegistrationController controller;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    registrationService = mock(RegistrationService.class);
    controller = new RegistrationController(registrationService);
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void shouldEmitRedirectOnlyAfterSaveCompletes() {
    RegistrationForm form = form();
    Sinks.One<User> completion = Sinks.one();
    when(registrationService.register(form)).thenReturn(completion.asMono());

    StepVerifier.create(controller.processRegistration(form))
        .then(() -> {
          verify(registrationService).register(form);
          assertEquals(Sinks.EmitResult.OK, completion.tryEmitValue(user()));
        })
        .expectNext("redirect:/login")
        .verifyComplete();
  }

  @Test
  void shouldRedirectHttpRequestAfterRegistrationCompletes() throws Exception {
    when(registrationService.register(any())).thenReturn(Mono.just(user()));

    MvcResult pending = mvc.perform(validPost())
        .andExpect(request().asyncStarted())
        .andReturn();

    mvc.perform(asyncDispatch(pending))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
  }

  @Test
  void shouldReturnConflictWhenUniqueIndexWinsRace() throws Exception {
    when(registrationService.register(any()))
        .thenReturn(Mono.error(new RegistrationConflictException()));

    MvcResult pending = mvc.perform(validPost())
        .andExpect(request().asyncStarted())
        .andReturn();

    mvc.perform(asyncDispatch(pending))
        .andExpect(status().isConflict());
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validPost() {
    return post("/register")
        .param("username", "alice")
        .param("password", "correct horse battery staple")
        .param("email", "alice@example.test");
  }

  private RegistrationForm form() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("alice");
    form.setPassword("correct horse battery staple");
    form.setEmail("alice@example.test");
    return form;
  }

  private User user() {
    return new User("alice", "{bcrypt}stored", "Alice", "Street", "City",
        "ST", "00000", "0000000000", "alice@example.test");
  }
}
