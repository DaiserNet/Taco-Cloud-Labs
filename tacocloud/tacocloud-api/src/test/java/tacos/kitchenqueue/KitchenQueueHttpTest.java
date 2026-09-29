package tacos.kitchenqueue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.api.dto.KitchenOrderResponse;
import tacos.security.SecurityConfig;

@WebMvcTest(controllers = {KitchenQueueController.class, KitchenUI.class})
@ContextConfiguration(classes = {SecurityConfig.class,
    KitchenQueueController.class, KitchenUI.class})
class KitchenQueueHttpTest {
  @Autowired private MockMvc mvc;
  @MockBean private UserDetailsService userDetailsService;
  @MockBean private KitchenQueueService queue;

  private KitchenOrderResponse order;

  @BeforeEach
  void setUp() {
    order = new KitchenOrderResponse();
    order.setId("O1");
    order.setPlacedAt(new Date(1000));
    order.setStatus(OrderStatus.ACCEPTED);
    order.setVersion(1L);
    order.setStationId("station:cook");
    order.setCookId("cook");
    order.setEstimatedPrepMinutes(8);
    order.setItems(Collections.emptyList());
  }

  @Test
  void shouldExposeSafeQueueOnlyToKitchen() throws Exception {
    when(queue.queue(any(Authentication.class))).thenReturn(Flux.just(order));
    perform(get("/api/kitchen/queue").with(user("cook").roles("KITCHEN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("O1"))
        .andExpect(jsonPath("$[0].estimatedPrepMinutes").value(8))
        .andExpect(jsonPath("$[0].deliveryStreet").doesNotExist())
        .andExpect(jsonPath("$[0].paymentLast4").doesNotExist());
    mvc.perform(get("/api/kitchen/queue").with(user("alice").roles("USER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/kitchen/queue").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/kitchen/queue"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldRequireCsrfAndKitchenRoleForClaim() throws Exception {
    when(queue.claim(any(Authentication.class))).thenReturn(Mono.just(order));
    mvc.perform(post("/api/kitchen/claim")
        .with(user("cook").roles("KITCHEN")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/kitchen/claim")
        .with(user("alice").roles("USER")).with(csrf()))
        .andExpect(status().isForbidden());
    perform(post("/api/kitchen/claim")
        .with(user("cook").roles("KITCHEN")).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stationId").value("station:cook"))
        .andExpect(jsonPath("$.paymentMethodId").doesNotExist());
  }

  @Test
  void shouldAdvanceThroughSafeKitchenRoute() throws Exception {
    order.setStatus(OrderStatus.PREPARING);
    when(queue.advance(eq("O1"), eq(OrderStatus.PREPARING), eq(1L),
        eq("Started"), any(Authentication.class))).thenReturn(Mono.just(order));
    perform(patch("/api/kitchen/orders/O1/status")
        .with(user("cook").roles("KITCHEN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"PREPARING\",\"expectedVersion\":1,"
            + "\"reason\":\"Started\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PREPARING"))
        .andExpect(jsonPath("$.deliveryStreet").doesNotExist());
    mvc.perform(patch("/api/orders/O1/status")
        .with(user("cook").roles("KITCHEN")).with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldServeWorkingKitchenPageOnlyToKitchen() throws Exception {
    mvc.perform(get("/api/kitchen/ui").with(user("cook").roles("KITCHEN")))
        .andExpect(status().isOk())
        .andExpect(cookie().exists("XSRF-TOKEN"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(
            "Reclamar siguiente")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(
            "/api/kitchen/claim")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(
            "/api/kitchen/orders/")));
    mvc.perform(get("/api/kitchen/ui").with(user("alice").roles("USER")))
        .andExpect(status().isForbidden());
  }

  private org.springframework.test.web.servlet.ResultActions perform(
      MockHttpServletRequestBuilder builder) throws Exception {
    MvcResult pending = mvc.perform(builder)
        .andExpect(request().asyncStarted()).andReturn();
    return mvc.perform(asyncDispatch(pending));
  }
}
