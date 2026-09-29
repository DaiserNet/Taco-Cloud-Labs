package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.Date;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.OrderStatusChange;
import tacos.TacoOrder;
import tacos.api.error.ApiExceptionHandler;
import tacos.api.mapper.OrderMapper;
import tacos.workflow.OrderWorkflowService;

class OrderWorkflowControllerTest {
  private OrderWorkflowService workflow;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    workflow = mock(OrderWorkflowService.class);
    mvc = MockMvcBuilders.standaloneSetup(
        new OrderWorkflowController(workflow, new OrderMapper()))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test
  void shouldExposeStatusVersionAndOrderedAudit() throws Exception {
    TacoOrder order = new TacoOrder();
    order.setId("O1");
    order.setStatus(OrderStatus.ACCEPTED);
    order.setVersion(1L);
    order.setStatusHistory(Collections.singletonList(
        new OrderStatusChange(OrderStatus.CREATED, OrderStatus.ACCEPTED,
            "cook", "ROLE_KITCHEN", new Date(1000), "KITCHEN_API",
            "Kitchen accepted")));
    when(workflow.changeStatus(eq("O1"), eq(OrderStatus.ACCEPTED), eq(0L),
        eq("Kitchen accepted"), any(Authentication.class)))
        .thenReturn(Mono.just(order));

    perform(patch("/api/orders/O1/status")
        .content("{\"status\":\"ACCEPTED\",\"expectedVersion\":0,"
            + "\"reason\":\"Kitchen accepted\"}"), admin())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.statusHistory[0].actorRole")
            .value("ROLE_KITCHEN"))
        .andExpect(jsonPath("$.statusHistory[0].reason")
            .value("Kitchen accepted"))
        .andExpect(jsonPath("$.paymentMethodId").doesNotExist())
        .andExpect(jsonPath("$.user").doesNotExist());
  }

  @Test
  void shouldExposeOwnerCancellationWithoutDeletingOrder() throws Exception {
    TacoOrder cancelled = new TacoOrder();
    cancelled.setId("O1");
    cancelled.setStatus(OrderStatus.CANCELLED);
    cancelled.setVersion(1L);
    when(workflow.cancel(eq("O1"), eq(0L), eq("Changed my mind"),
        any(Authentication.class))).thenReturn(Mono.just(cancelled));

    perform(post("/api/orders/O1/cancel")
        .content("{\"expectedVersion\":0,"
            + "\"reason\":\"Changed my mind\"}"), customer())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.id").value("O1"));
  }

  @Test
  void shouldRejectClientOwnedFieldsAndMissingVersion() throws Exception {
    mvc.perform(patch("/api/orders/O1/status")
        .principal(admin()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"READY\",\"reason\":\"done\","
            + "\"userId\":\"other\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/orders/O1/cancel")
        .principal(customer()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"changed mind\"}"))
        .andExpect(status().isUnprocessableEntity());
    verifyNoInteractions(workflow);
  }

  @Test
  void shouldMapOptimisticLockConflictToHttp409() throws Exception {
    when(workflow.changeStatus(eq("O1"), eq(OrderStatus.ACCEPTED), eq(0L),
        eq("accepted"), any(Authentication.class)))
        .thenReturn(Mono.error(new OptimisticLockingFailureException(
            "stale order")));

    perform(patch("/api/orders/O1/status")
        .content("{\"status\":\"ACCEPTED\",\"expectedVersion\":0,"
            + "\"reason\":\"accepted\"}"), admin())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_VERSION_CONFLICT"));
  }

  @Test
  void shouldKeepAuditOutOfRawKitchenOrderSerialization() throws Exception {
    TacoOrder order = new TacoOrder();
    order.setStatusHistory(Collections.singletonList(
        new OrderStatusChange(OrderStatus.CREATED, OrderStatus.ACCEPTED,
            "cook", "ROLE_KITCHEN", new Date(1000), "KITCHEN_API",
            "Kitchen accepted")));

    String json = new ObjectMapper().writeValueAsString(order);
    assertFalse(json.contains("statusHistory"));
    assertFalse(json.contains("Kitchen accepted"));
  }

  private org.springframework.test.web.servlet.ResultActions perform(
      MockHttpServletRequestBuilder request, Authentication actor)
      throws Exception {
    MvcResult pending = mvc.perform(request.principal(actor)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON))
        .andExpect(request().asyncStarted()).andReturn();
    return mvc.perform(asyncDispatch(pending));
  }

  private Authentication customer() {
    return auth("alice", "ROLE_USER");
  }

  private Authentication admin() {
    return auth("admin", "ROLE_ADMIN");
  }

  private Authentication auth(String name, String role) {
    return new UsernamePasswordAuthenticationToken(name, "unused",
        AuthorityUtils.createAuthorityList(role));
  }
}
