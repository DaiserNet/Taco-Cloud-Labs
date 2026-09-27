package tacos.web.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import tacos.PaymentMethod;
import tacos.User;
import tacos.payment.PaymentMethodService;
import tacos.payment.PaymentTokenizationCommand;

class PaymentMethodControllerTest {

  @Test
  void shouldReturnCreatedWithSafeSummaryOnly() throws Exception {
    PaymentMethodService service = mock(PaymentMethodService.class);
    PaymentMethod method = new PaymentMethod(
        user(), "tok_internal", "VISA", "0002", "12/99");
    method.setId("PAYMENT-ID");
    Authentication authentication = authentication();
    PaymentTokenizationCommand expected = new PaymentTokenizationCommand(
        "4000000000000002", "12/99", "123");
    when(service.tokenize(expected, authentication)).thenReturn(Mono.just(method));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
        new PaymentMethodController(service)).build();

    MvcResult result = mvc.perform(post("/api/payment-methods/tokenize")
            .principal(authentication)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{\"pan\":\"4000000000000002\"," +
                "\"expiration\":\"12/99\",\"cvv\":\"123\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    mvc.perform(asyncDispatch(result))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.paymentMethodId").value("PAYMENT-ID"))
        .andExpect(jsonPath("$.brand").value("VISA"))
        .andExpect(jsonPath("$.last4").value("0002"))
        .andExpect(jsonPath("$.paymentToken").doesNotExist())
        .andExpect(jsonPath("$.pan").doesNotExist())
        .andExpect(jsonPath("$.cvv").doesNotExist())
        .andExpect(jsonPath("$.expiration").doesNotExist());

    verify(service).tokenize(expected, authentication);
  }

  private User user() {
    User user = new User("alice", "N/A", "Alice", "Street", "City", "ST",
        "00000", "0000000000", "alice@example.test");
    user.setId("alice-id");
    return user;
  }

  private Authentication authentication() {
    return new UsernamePasswordAuthenticationToken("alice", "N/A",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
