package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.IngredientRequest;
import tacos.api.dto.IngredientResponse;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.IngredientMapper;
import tacos.api.mapper.OrderMapper;
import tacos.history.OrderHistoryService;

class ApiDtoContractTest {

  private OrderService orderService;
  private OrderHistoryService historyService;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    orderService = mock(OrderService.class);
    historyService = mock(OrderHistoryService.class);
    OrderApiController controller = new OrderApiController(orderService, new OrderMapper());
    client = WebTestClient.bindToController(controller,
        new OrderHistoryController(historyService)).build();
  }

  @Test
  void shouldNotSerializeSensitiveOrderOrEmbeddedUserFields() {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-ID");
    User user = new User("owner", "secret-password", "Owner", "Street",
        "City", "ST", "00000", "0000000000", "owner@example.test");
    user.setId("USER-ID");
    order.setUser(user);
    order.setPaymentMethodId("PAYMENT-ID");
    order.setPaymentBrand("VISA");
    order.setPaymentLast4("1111");
    when(historyService.myOrder("ORDER-ID", null))
        .thenReturn(Mono.just(new OrderMapper().toResponse(order)));

    client.get().uri("/api/orders/me/ORDER-ID").exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("ORDER-ID")
        .jsonPath("$.userId").isEqualTo("USER-ID")
        .jsonPath("$.paymentBrand").isEqualTo("VISA")
        .jsonPath("$.paymentLast4").isEqualTo("1111")
        .jsonPath("$.user").doesNotExist()
        .jsonPath("$.password").doesNotExist()
        .jsonPath("$.authorities").doesNotExist()
        .jsonPath("$.paymentMethodId").doesNotExist()
        .jsonPath("$.ccNumber").doesNotExist()
        .jsonPath("$.ccExpiration").doesNotExist()
        .jsonPath("$.ccCVV").doesNotExist();
  }

  @Test
  void shouldRejectOrderServerOwnedFieldsWithoutEffects() {
    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"id\":\"CLIENT-ID\",\"deliveryName\":\"Client\","
            + "\"tacos\":[]}")
        .exchange()
        .expectStatus().isBadRequest();

    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"DELIVERED\",\"deliveryName\":\"Client\"}")
        .exchange()
        .expectStatus().isBadRequest();

    verifyNoInteractions(orderService);
  }

  @Test
  void shouldMapOrderRequestWithoutServerOwnedFields() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Delivery Name");
    request.setDeliveryStreet("Street");
    request.setDeliveryCity("City");
    request.setDeliveryState("ST");
    request.setDeliveryZip("00000");
    request.setPaymentMethodId("PAYMENT-ID");
    OrderCreateRequest.TacoItem taco = new OrderCreateRequest.TacoItem();
    taco.setName("Reference taco");
    taco.setIngredientIds(Arrays.asList("WRAP"));
    OrderCreateRequest.OrderItem item = new OrderCreateRequest.OrderItem();
    item.setTaco(taco);
    item.setQuantity(2);
    request.setItems(Arrays.asList(item));

    TacoOrder order = new OrderMapper().toEntity(request);

    assertNull(order.getId());
    assertNull(order.getUser());
    assertNotNull(order.getPlacedAt());
    assertEquals(OrderStatus.CREATED, order.getStatus());
    assertEquals("Delivery Name", order.getDeliveryName());
    assertEquals("PAYMENT-ID", order.getPaymentMethodId());
    assertEquals(2, order.getItems().get(0).getQuantity());
    assertEquals("Reference taco", order.getItems().get(0).getTaco().getName());
    assertEquals("WRAP",
        order.getItems().get(0).getTaco().getIngredients().get(0).getId());
    assertNull(order.getItems().get(0).getTaco().getIngredients().get(0).getName());
  }

  @Test
  void shouldMapEntityToDocumentedSafeResponseJson() throws Exception {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-ID");
    order.setPlacedAt(new Date(0));
    order.setPaymentMethodId("PAYMENT-ID");
    order.setPaymentBrand("VISA");
    order.setPaymentLast4("1111");
    User user = new User("owner", "secret", "Owner", "Street", "City", "ST",
        "00000", "0000000000", "owner@example.test");
    user.setId("USER-ID");
    order.setUser(user);

    OrderResponse response = new OrderMapper().toResponse(order);
    JsonNode json = new ObjectMapper().valueToTree(response);
    Set<String> actualFields = new LinkedHashSet<>();
    json.fieldNames().forEachRemaining(actualFields::add);

    assertEquals(new LinkedHashSet<>(Arrays.asList(
        "id", "placedAt", "status", "version", "statusHistory", "userId",
        "deliveryName", "deliveryStreet",
        "deliveryCity", "deliveryState", "deliveryZip", "paymentBrand",
        "paymentLast4", "currency", "subtotal", "discount", "total",
        "couponApplied", "items")),
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
    assertEquals(new IngredientResponse("TEST", "Test ingredient", Type.SAUCE,
        new BigDecimal("0.00"), false), response);
  }

  @Test
  void shouldExposeTypedIngredientMetadataAndPreserveItWhenPutOmitsIt() {
    IngredientRequest request = new IngredientRequest();
    request.setId("SLSA");
    request.setName("Sesame salsa");
    request.setType(Type.SAUCE);
    request.setDietaryTags(EnumSet.of(DietaryTag.VEGAN,
        DietaryTag.GLUTEN_FREE));
    request.setAllergens(EnumSet.of(Allergen.SESAME));
    request.setSpiceLevel(SpiceLevel.HOT);
    IngredientMapper mapper = new IngredientMapper();

    Ingredient entity = mapper.toEntity(request);
    IngredientResponse response = mapper.toResponse(entity);
    assertEquals(request.getDietaryTags(), response.getDietaryTags());
    assertEquals(request.getAllergens(), response.getAllergens());
    assertEquals(SpiceLevel.HOT, response.getSpiceLevel());

    IngredientRequest oldClientPut = new IngredientRequest();
    oldClientPut.setId("SLSA");
    oldClientPut.setName("Renamed salsa");
    oldClientPut.setType(Type.SAUCE);
    mapper.updateEntity(oldClientPut, entity);
    assertEquals(request.getDietaryTags(), entity.getDietaryTags());
    assertEquals(request.getAllergens(), entity.getAllergens());
    assertEquals(SpiceLevel.HOT, entity.getSpiceLevel());
  }
}
