package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import tacos.api.dto.OrderResponse;
import tacos.api.dto.ReorderConfirmResponse;
import tacos.api.dto.ReorderQuoteResponse;
import tacos.api.dto.ReorderRequest;
import tacos.reorder.ReorderService;

class ReorderControllerTest {
  private static final String FINGERPRINT = String.join("",
      Collections.nCopies(64, "a"));

  private ReorderService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(ReorderService.class);
    mvc = MockMvcBuilders.standaloneSetup(new ReorderController(service))
        .build();
  }

  @Test
  void shouldReturnQuoteSeparatelyFromConfirmation() throws Exception {
    ReorderQuoteResponse quote = new ReorderQuoteResponse("OLD", "USD",
        new BigDecimal("3.00"), new BigDecimal("3.50"),
        new BigDecimal("3.00"), new BigDecimal("3.50"),
        new BigDecimal("0.50"), false, Collections.emptyList(),
        FINGERPRINT, true);
    when(service.quote(eq("OLD"), any(ReorderRequest.class),
        any(Authentication.class))).thenReturn(Mono.just(quote));

    perform(post("/api/orders/me/OLD/reorder/quote")
        .content("{\"paymentMethodId\":\"PAY1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceOrderId").value("OLD"))
        .andExpect(jsonPath("$.priceDifference").value(0.5))
        .andExpect(jsonPath("$.confirmationRequired").value(true))
        .andExpect(jsonPath("$.order").doesNotExist());
  }

  @Test
  void shouldReturnCreatedOnceAndOkOnReplay() throws Exception {
    OrderResponse order = new OrderResponse();
    order.setId("NEW");
    when(service.confirm(eq("OLD"), any(ReorderRequest.class),
        eq("request-key"), any(Authentication.class)))
        .thenReturn(Mono.just(new ReorderConfirmResponse(order, false)),
            Mono.just(new ReorderConfirmResponse(order, true)));
    MockHttpServletRequestBuilder request = post("/api/orders/me/OLD/reorder")
        .header("Idempotency-Key", "request-key")
        .content("{\"paymentMethodId\":\"PAY1\","
            + "\"quoteFingerprint\":\"" + FINGERPRINT + "\"}");

    perform(request).andExpect(status().isCreated())
        .andExpect(jsonPath("$.order.id").value("NEW"))
        .andExpect(jsonPath("$.replayed").value(false));
    perform(post("/api/orders/me/OLD/reorder")
        .header("Idempotency-Key", "request-key")
        .content("{\"paymentMethodId\":\"PAY1\","
            + "\"quoteFingerprint\":\"" + FINGERPRINT + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.id").value("NEW"))
        .andExpect(jsonPath("$.replayed").value(true));
  }

  @Test
  void shouldRejectClonedIdentityOrPaymentTokenInRequest() throws Exception {
    mvc.perform(post("/api/orders/me/OLD/reorder/quote")
        .principal(user()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"paymentMethodId\":\"PAY1\",\"id\":\"OLD\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/orders/me/OLD/reorder/quote")
        .principal(user()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"paymentMethodId\":\"PAY1\","
            + "\"paymentToken\":\"stale\"}"))
        .andExpect(status().isBadRequest());
  }

  private org.springframework.test.web.servlet.ResultActions perform(
      MockHttpServletRequestBuilder request) throws Exception {
    MvcResult pending = mvc.perform(request.principal(user())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON))
        .andExpect(request().asyncStarted()).andReturn();
    return mvc.perform(asyncDispatch(pending));
  }

  private Authentication user() {
    return new UsernamePasswordAuthenticationToken("alice", "unused",
        AuthorityUtils.createAuthorityList("ROLE_USER"));
  }
}
