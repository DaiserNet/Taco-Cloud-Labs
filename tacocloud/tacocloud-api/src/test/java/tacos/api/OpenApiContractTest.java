package tacos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.stereotype.Controller;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.error.ApiExceptionHandler;
import tacos.api.mapper.OrderMapper;
import tacos.idempotency.OrderIdempotencyService;
import tacos.web.api.OrderApiController;
import tacos.web.api.OrderService;

class OpenApiContractTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void yamlParsesWithoutValidationMessages() throws IOException {
    SwaggerParseResult parsed = parse();
    assertNotNull(parsed.getOpenAPI());
    assertTrue(parsed.getMessages().isEmpty(), parsed.getMessages().toString());
    assertEquals("3.0.3", parsed.getOpenAPI().getOpenapi());
  }

  @Test
  void everyWriteDocumentsTheRequiredCsrfHeader() throws Exception {
    OpenAPI spec = parse().getOpenAPI();
    assertTrue(spec.getComponents().getParameters().get("CsrfToken")
        .getRequired());
    spec.getPaths().forEach((path, item) -> item.readOperationsMap()
        .forEach((verb, operation) -> {
          if (verb == PathItem.HttpMethod.GET) {
            return;
          }
          assertNotNull(operation.getParameters(), verb + " " + path);
          assertTrue(operation.getParameters().stream().anyMatch(parameter ->
                  "#/components/parameters/CsrfToken".equals(
                      parameter.get$ref())),
              "Missing CSRF header: " + verb + " " + path);
        }));
  }

  @Test
  void everyImplementedApiRouteHasAV1AliasAndOpenApiOperation()
      throws Exception {
    OpenAPI spec = parse().getOpenAPI();
    Set<String> implemented = new HashSet<>();
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
    for (BeanDefinition candidate : scanner.findCandidateComponents("tacos")) {
      Class<?> controller = Class.forName(candidate.getBeanClassName());
      RequestMapping baseMapping = AnnotatedElementUtils.findMergedAnnotation(
          controller, RequestMapping.class);
      String[] bases = paths(baseMapping);
      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
            method, RequestMapping.class);
        if (mapping == null || mapping.method().length == 0) {
          continue;
        }
        Set<String> routes = new HashSet<>();
        for (String base : bases) {
          for (String suffix : paths(mapping)) {
            routes.add(join(base, suffix));
          }
        }
        for (String route : routes) {
          if (!route.startsWith("/api/") || route.startsWith("/api/v1/")) {
            continue;
          }
          String v1 = "/api/v1" + route.substring(4);
          assertTrue(routes.contains(v1), method + " has no v1 alias for " + route);
          for (org.springframework.web.bind.annotation.RequestMethod verb
              : mapping.method()) {
            String operation = verb.name() + " " + v1;
            implemented.add(operation);
            assertNotNull(spec.getPaths().get(v1), "Missing path " + v1);
            assertNotNull(spec.getPaths().get(v1).readOperationsMap().get(
                PathItem.HttpMethod.valueOf(verb.name())),
                "Missing operation " + operation);
          }
        }
      }
    }
    assertFalse(implemented.isEmpty());
    spec.getPaths().forEach((path, item) -> item.readOperationsMap()
        .forEach((verb, operation) -> assertTrue(
            implemented.contains(verb.name() + " " + path),
            "Documented endpoint is not implemented: " + verb + " " + path)));
  }

  @Test
  void postOrderAndApiProblemMatchDocumentedStatusAndSchema()
      throws Exception {
    OpenAPI spec = parse().getOpenAPI();
    OrderIdempotencyService idempotency = mock(OrderIdempotencyService.class);
    OrderResponse order = new OrderResponse();
    order.setId("ORDER-ID");
    order.setStatus(OrderStatus.CREATED);
    order.setUserId("USER-ID");
    order.setPaymentBrand("VISA");
    order.setPaymentLast4("0002");
    order.setItems(Collections.emptyList());
    when(idempotency.create(any(OrderCreateRequest.class), eq("test-key-123"),
        any())).thenReturn(Mono.just(order));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderApiController(
        mock(OrderService.class), new OrderMapper(), idempotency))
        .setControllerAdvice(new ApiExceptionHandler()).build();
    String validBody = "{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"Street\","
        + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
        + "\"deliveryZip\":\"78701\",\"paymentMethodId\":\"PAYMENT-ID\","
        + "\"items\":[{\"taco\":{\"name\":\"Garden taco\","
        + "\"ingredientIds\":[\"WRAP\",\"SLSA\"]},\"quantity\":2}]}";
    UsernamePasswordAuthenticationToken owner =
        new UsernamePasswordAuthenticationToken("alice", "password",
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

    MvcResult pending = mvc.perform(post("/api/v1/orders")
        .header("Idempotency-Key", "test-key-123")
        .principal(owner).contentType(MediaType.APPLICATION_JSON)
        .content(validBody)).andExpect(request().asyncStarted()).andReturn();
    MvcResult created = mvc.perform(asyncDispatch(pending))
        .andExpect(status().isCreated()).andReturn();
    assertDocumented(spec, "/api/v1/orders", "post", created);

    String unknownField = validBody.substring(0, validBody.length() - 1)
        + ",\"ccNumber\":\"4111111111111111\"}";
    MvcResult rejected = mvc.perform(post("/api/v1/orders")
        .header("Idempotency-Key", "test-key-123")
        .principal(owner).contentType(MediaType.APPLICATION_JSON)
        .content(unknownField)).andExpect(status().isBadRequest()).andReturn();
    assertDocumented(spec, "/api/v1/orders", "post", rejected);
    assertEquals("MALFORMED_REQUEST", json.readTree(rejected.getResponse()
        .getContentAsString()).get("code").asText());
  }

  @Test
  void contractRejectsUndocumentedSensitiveFieldsAndStatus() throws Exception {
    OpenAPI spec = parse().getOpenAPI();
    Schema<?> response = spec.getComponents().getSchemas().get("OrderResponse");
    assertFalse(response.getProperties().containsKey("paymentMethodId"));
    assertFalse(response.getProperties().containsKey("paymentToken"));
    assertFalse(response.getProperties().containsKey("pan"));
    assertFalse(response.getProperties().containsKey("cvv"));
    assertFalse(response.getProperties().containsKey("user"));
    assertFalse(spec.getComponents().getSchemas().get("IngredientResponse")
        .getProperties().containsKey("stockOnHand"));
    ObjectNode leaked = json.createObjectNode();
    leaked.put("id", "ORDER-ID");
    leaked.put("paymentToken", "secret");
    assertThrows(AssertionError.class, () -> assertBody(spec, response, leaked));
    assertFalse(spec.getPaths().get("/api/v1/orders").getPost()
        .getResponses().containsKey("202"));
  }

  private SwaggerParseResult parse() throws IOException {
    ClassPathResource resource = new ClassPathResource("static/openapi.yaml");
    String yaml = StreamUtils.copyToString(resource.getInputStream(),
        StandardCharsets.UTF_8);
    return new OpenAPIV3Parser().readContents(yaml, null, null);
  }

  private String[] paths(RequestMapping mapping) {
    if (mapping == null) {
      return new String[] { "" };
    }
    String[] values = mapping.path().length == 0 ? mapping.value() : mapping.path();
    return values.length == 0 ? new String[] { "" } : values;
  }

  private String join(String base, String suffix) {
    if (base.isEmpty()) {
      return suffix;
    }
    if (suffix.isEmpty()) {
      return base;
    }
    return base + (suffix.startsWith("/") ? suffix : "/" + suffix);
  }

  private void assertDocumented(OpenAPI spec, String path, String verb,
      MvcResult result) throws Exception {
    io.swagger.v3.oas.models.Operation operation = spec.getPaths().get(path)
        .readOperationsMap().get(PathItem.HttpMethod.valueOf(verb.toUpperCase()));
    String status = String.valueOf(result.getResponse().getStatus());
    ApiResponse response = operation.getResponses().get(status);
    assertNotNull(response, "HTTP " + status + " missing from " + verb + " " + path);
    if (response.get$ref() != null) {
      response = spec.getComponents().getResponses().get(response.get$ref()
          .substring("#/components/responses/".length()));
    }
    assertNotNull(response);
    String mediaType = result.getResponse().getContentType().split(";")[0];
    assertNotNull(response.getContent().get(mediaType));
    assertBody(spec, response.getContent().get(mediaType).getSchema(),
        json.readTree(result.getResponse().getContentAsString()));
  }

  private void assertBody(OpenAPI spec, Schema<?> input, JsonNode body) {
    Schema<?> schema = input.get$ref() == null ? input
        : spec.getComponents().getSchemas().get(input.get$ref()
            .substring("#/components/schemas/".length()));
    assertNotNull(schema);
    if (body.isNull()) {
      return;
    }
    if ("object".equals(schema.getType())) {
      assertTrue(body.isObject());
      List<String> required = schema.getRequired() == null
          ? Collections.emptyList() : schema.getRequired();
      required.forEach(name -> assertTrue(body.has(name), "Missing " + name));
      body.fields().forEachRemaining(field -> {
        Schema<?> property = schema.getProperties() == null ? null
            : (Schema<?>) schema.getProperties().get(field.getKey());
        assertNotNull(property, "Unexpected response field " + field.getKey());
        assertBody(spec, property, field.getValue());
      });
    } else if ("array".equals(schema.getType())) {
      assertTrue(body.isArray());
      body.forEach(item -> assertBody(spec, schema.getItems(), item));
    } else if ("string".equals(schema.getType())) {
      assertTrue(body.isTextual());
    } else if ("integer".equals(schema.getType())) {
      assertTrue(body.isIntegralNumber());
    } else if ("number".equals(schema.getType())) {
      assertTrue(body.isNumber());
    } else if ("boolean".equals(schema.getType())) {
      assertTrue(body.isBoolean());
    }
  }
}
