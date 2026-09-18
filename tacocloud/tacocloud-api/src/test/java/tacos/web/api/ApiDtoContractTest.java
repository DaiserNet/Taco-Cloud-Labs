package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.validation.Validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.IngredientRequest;
import tacos.api.dto.IngredientResponse;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.IngredientMapper;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

class ApiDtoContractTest {

  private OrderRepository repo;
  private OrderMessagingService messaging;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    repo = mock(OrderRepository.class);
    messaging = mock(OrderMessagingService.class);
    OrderApiController controller = new OrderApiController(
        repo, messaging, mock(EmailOrderService.class), mock(Validator.class),
        new OrderMapper());
    client = WebTestClient.bindToController(controller).build();
  }

  @Test
  void shouldNotSerializeSensitiveOrderOrEmbeddedUserFields() {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-ID");
    User user = new User("owner", "secret-password", "Owner", "Street",
        "City", "ST", "00000", "0000000000", "owner@example.test");
    user.setId("USER-ID");
    order.setUser(user);
    order.setCcNumber("4111111111111111");
    order.setCcExpiration("12/99");
    order.setCcCVV("123");
    when(repo.findAll()).thenReturn(Flux.just(order));

    client.get().uri("/api/orders").exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[0].id").isEqualTo("ORDER-ID")
        .jsonPath("$[0].userId").isEqualTo("USER-ID")
        .jsonPath("$[0].paymentLast4").isEqualTo("1111")
        .jsonPath("$[0].user").doesNotExist()
        .jsonPath("$[0].password").doesNotExist()
        .jsonPath("$[0].authorities").doesNotExist()
        .jsonPath("$[0].ccNumber").doesNotExist()
        .jsonPath("$[0].ccExpiration").doesNotExist()
        .jsonPath("$[0].ccCVV").doesNotExist();
  }

  @Test
  void shouldRejectOrderServerOwnedFieldsWithoutEffects() {
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"id\":\"CLIENT-ID\",\"deliveryName\":\"Client\","
            + "\"tacos\":[]}")
        .exchange()
        .expectStatus().isBadRequest();

    verifyNoInteractions(repo, messaging);
  }

  @Test
  void shouldMapOrderRequestWithoutServerOwnedFields() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Delivery Name");
    request.setDeliveryStreet("Street");
    request.setDeliveryCity("City");
    request.setDeliveryState("ST");
    request.setDeliveryZip("00000");
    request.setCcNumber("4111111111111111");
    request.setCcExpiration("12/99");
    request.setCcCVV("123");
    OrderCreateRequest.IngredientItem ingredient =
        new OrderCreateRequest.IngredientItem();
    ingredient.setId("WRAP");
    OrderCreateRequest.TacoItem taco = new OrderCreateRequest.TacoItem();
    taco.setId("TACO-ID");
    taco.setName("Reference taco");
    taco.setIngredients(Arrays.asList(ingredient));
    request.setTacos(Arrays.asList(taco));

    TacoOrder order = new OrderMapper().toEntity(request);

    assertNull(order.getId());
    assertNull(order.getUser());
    assertNotNull(order.getPlacedAt());
    assertEquals(OrderStatus.PLACED, order.getStatus());
    assertEquals("Delivery Name", order.getDeliveryName());
    assertEquals("4111111111111111", order.getCcNumber());
    assertEquals("TACO-ID", order.getTacos().get(0).getId());
    assertEquals("WRAP", order.getTacos().get(0).getIngredients().get(0).getId());
    assertNull(order.getTacos().get(0).getIngredients().get(0).getName());
  }

  @Test
  void shouldMapEntityToDocumentedSafeResponseJson() throws Exception {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-ID");
    order.setPlacedAt(new Date(0));
    order.setCcNumber("4111111111111111");
    order.setCcExpiration("12/99");
    order.setCcCVV("123");
    User user = new User("owner", "secret", "Owner", "Street", "City", "ST",
        "00000", "0000000000", "owner@example.test");
    user.setId("USER-ID");
    order.setUser(user);

    OrderResponse response = new OrderMapper().toResponse(order);
    JsonNode json = new ObjectMapper().valueToTree(response);
    Set<String> actualFields = new LinkedHashSet<>();
    json.fieldNames().forEachRemaining(actualFields::add);

    assertEquals(new LinkedHashSet<>(Arrays.asList(
        "id", "placedAt", "status", "userId", "deliveryName", "deliveryStreet",
        "deliveryCity", "deliveryState", "deliveryZip", "paymentLast4", "tacos")),
        actualFields);
    assertEquals("USER-ID", json.get("userId").asText());
    assertEquals("1111", json.get("paymentLast4").asText());
    assertNull(json.get("ccNumber"));
    assertNull(json.get("ccExpiration"));
    assertNull(json.get("ccCVV"));
    assertNull(json.get("user"));
  }

  @Test
  void shouldMapIngredientRequestAndEntityToDedicatedContracts() {
    IngredientRequest request = new IngredientRequest();
    request.setId("TEST");
    request.setName("Test ingredient");
    request.setType(Type.SAUCE);
    IngredientMapper mapper = new IngredientMapper();

    Ingredient entity = mapper.toEntity(request);
    IngredientResponse response = mapper.toResponse(entity);

    assertEquals(new Ingredient("TEST", "Test ingredient", Type.SAUCE), entity);
    assertEquals(new IngredientResponse("TEST", "Test ingredient", Type.SAUCE), response);
  }
}
