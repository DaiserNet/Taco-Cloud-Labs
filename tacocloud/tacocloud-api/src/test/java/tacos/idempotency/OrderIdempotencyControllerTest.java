package tacos.idempotency;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.OrderMapper;
import tacos.web.api.OrderApiController;
import tacos.web.api.OrderService;

class OrderIdempotencyControllerTest {
  @Test
  void repeatedPostWithHeaderKeepsCreatedStatusAndOrderId() throws Exception {
    OrderIdempotencyService idempotency = mock(OrderIdempotencyService.class);
    OrderResponse response = new OrderResponse();
    response.setId("ORDER-123");
    when(idempotency.create(any(OrderCreateRequest.class), eq("repeat-key-1"),
        any())).thenReturn(Mono.just(response));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderApiController(
        mock(OrderService.class), new OrderMapper(), idempotency)).build();
    String body = "{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"Street\","
        + "\"deliveryCity\":\"City\",\"deliveryState\":\"ST\","
        + "\"deliveryZip\":\"00000\",\"paymentMethodId\":\"PAYMENT-ID\","
        + "\"items\":[{\"taco\":{\"name\":\"Valid taco\","
        + "\"ingredientIds\":[\"WRAP\",\"SLSA\"]},\"quantity\":1}]}";
    UsernamePasswordAuthenticationToken owner =
        new UsernamePasswordAuthenticationToken("alice", "password",
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

    for (int attempt = 0; attempt < 2; attempt++) {
      MvcResult pending = mvc.perform(post("/api/orders")
          .contentType(MediaType.APPLICATION_JSON)
          .header("Idempotency-Key", "repeat-key-1")
          .principal(owner).content(body))
          .andExpect(request().asyncStarted()).andReturn();
      mvc.perform(asyncDispatch(pending))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.id").value("ORDER-123"));
    }
    verify(idempotency, times(2)).create(any(OrderCreateRequest.class),
        eq("repeat-key-1"), any());
  }
}
