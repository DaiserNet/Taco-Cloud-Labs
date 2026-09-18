package tacos.web.api;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.util.Collections;

import javax.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.error.ApiExceptionHandler;
import tacos.api.mapper.IngredientMapper;
import tacos.api.mapper.OrderMapper;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

class ApiProblemControllerTest {

  private OrderRepository orderRepo;
  private IngredientRepository ingredientRepo;
  private OrderMessagingService messaging;
  private EmailOrderService emailOrderService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    orderRepo = mock(OrderRepository.class);
    ingredientRepo = mock(IngredientRepository.class);
    messaging = mock(OrderMessagingService.class);
    emailOrderService = mock(EmailOrderService.class);
    OrderApiController orderController = new OrderApiController(
        orderRepo, messaging, emailOrderService, mock(Validator.class), new OrderMapper());
    mvc = MockMvcBuilders.standaloneSetup(
        orderController, new IngredientController(ingredientRepo, new IngredientMapper()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void shouldReturnUnprocessableProblemWithMultipleViolationsBeforeEffects() throws Exception {
    perform(post("/api/orders").content("{}"), null)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type")
            .value("urn:tacocloud:problem:validation-failed"))
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.detail")
            .value("One or more request fields are invalid."))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.instance").value("/api/orders"))
        .andExpect(jsonPath("$.violations[*].field",
            hasItems("deliveryName", "ccNumber", "tacos")));

    verifyNoInteractions(orderRepo, messaging);
  }

  @Test
  void shouldReturnBadRequestProblemForMalformedJson() throws Exception {
    perform(post("/api/orders").content("{"), null)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        .andExpect(jsonPath("$.instance").value("/api/orders"))
        .andExpect(jsonPath("$.stackTrace").doesNotExist());
  }

  @Test
  void shouldReturnNotFoundProblemForMissingIngredient() throws Exception {
    when(ingredientRepo.findById("MISSING")).thenReturn(Mono.empty());

    perform(get("/api/ingredients/MISSING"), null)
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.instance").value("/api/ingredients/MISSING"));
  }

  @Test
  void shouldReturnConflictProblemForPreparingOrder() throws Exception {
    TacoOrder order = order("ORDER", "owner");
    order.setStatus(OrderStatus.PREPARING);
    when(orderRepo.findById("ORDER")).thenReturn(Mono.just(order));

    perform(put("/api/orders/ORDER").content(validReplacement()), user("owner"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
  }

  @Test
  void shouldReturnForbiddenProblemForForeignOrder() throws Exception {
    when(orderRepo.findById("ORDER")).thenReturn(Mono.just(order("ORDER", "owner")));

    perform(put("/api/orders/ORDER").content(validReplacement()), user("other"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void shouldReturnUnprocessableProblemForEmailBusinessRule() throws Exception {
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.error(new EmailOrderIngredientNotFoundException("UNKNOWN")));

    perform(post("/api/orders/fromEmail").content(
        "{\"email\":\"owner@example.test\",\"tacos\":["
            + "{\"name\":\"Valid taco\",\"ingredients\":[\"UNKNOWN\"]}]}"), null)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("EMAIL_ORDER_INGREDIENT_NOT_FOUND"))
        .andExpect(jsonPath("$.detail").value("Unknown ingredient: UNKNOWN"));

    verifyNoInteractions(orderRepo, messaging);
  }

  @Test
  void shouldSanitizeInternalDataAccessProblem() throws Exception {
    when(ingredientRepo.findById("FAIL")).thenReturn(Mono.error(
        new DataAccessResourceFailureException("MongoDB driver secret detail")));

    perform(get("/api/ingredients/FAIL"), null)
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("DATA_ACCESS_ERROR"))
        .andExpect(jsonPath("$.detail").value("An internal data access error occurred."))
        .andExpect(jsonPath("$.stackTrace").doesNotExist())
        .andExpect(content().string(not(containsString("MongoDB driver secret detail"))));
  }

  private ResultActions perform(
      MockHttpServletRequestBuilder request, Principal principal) throws Exception {
    if (principal != null) {
      request.principal(principal);
    }
    ResultActions action = mvc.perform(request.contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON));
    MvcResult result = action.andReturn();
    if (result.getRequest().isAsyncStarted()) {
      return mvc.perform(MockMvcRequestBuilders.asyncDispatch(result));
    }
    return action;
  }

  private String validReplacement() {
    return "{\"deliveryName\":\"Name\",\"deliveryStreet\":\"Street\","
        + "\"deliveryCity\":\"City\",\"deliveryState\":\"ST\","
        + "\"deliveryZip\":\"00000\"}";
  }

  private TacoOrder order(String id, String username) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUser(new User(username, "N/A", "Owner", "Street", "City", "ST",
        "00000", "0000000000", username + "@example.test"));
    return order;
  }

  private UsernamePasswordAuthenticationToken user(String username) {
    return new UsernamePasswordAuthenticationToken(username, "N/A",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
