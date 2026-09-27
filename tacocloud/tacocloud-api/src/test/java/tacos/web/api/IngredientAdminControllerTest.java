package tacos.web.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.api.error.ApiExceptionHandler;
import tacos.api.mapper.IngredientMapper;
import tacos.catalog.IngredientCatalogService;
import tacos.data.IngredientRepository;

class IngredientAdminControllerTest {

  private IngredientRepository ingredientRepo;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    ingredientRepo = mock(IngredientRepository.class);
    IngredientCatalogService service = new IngredientCatalogService(ingredientRepo);
    client = MockMvcWebTestClient.bindToController(
        new IngredientAdminController(service, new IngredientMapper()))
        .controllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void shouldUpdateCatalogAndReturnOperationalMetadata() {
    Ingredient ingredient = ingredient(10, 3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));
    when(ingredientRepo.save(ingredient)).thenReturn(Mono.just(ingredient));

    client.patch().uri("/api/admin/ingredients/FLTO/catalog")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"expectedVersion\":3,\"unitPrice\":1.235,"
            + "\"reorderLevel\":4}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.unitPrice").isEqualTo(1.24)
        .jsonPath("$.stockOnHand").isEqualTo(10)
        .jsonPath("$.reorderLevel").isEqualTo(4)
        .jsonPath("$.version").isEqualTo(3);
  }

  @Test
  void shouldReturnUnprocessableProblemForNegativeStockAdjustment() {
    Ingredient ingredient = ingredient(2, 3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));

    client.post().uri("/api/admin/ingredients/FLTO/stock-adjustments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"expectedVersion\":3,\"adjustment\":-3}")
        .exchange()
        .expectStatus().isEqualTo(422)
        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code").isEqualTo("INGREDIENT_STOCK_NEGATIVE");
  }

  @Test
  void shouldReturnConflictProblemForStaleVersion() {
    Ingredient ingredient = ingredient(10, 3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));

    client.patch().uri("/api/admin/ingredients/FLTO/catalog")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"expectedVersion\":2,\"available\":false}")
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code").isEqualTo("INGREDIENT_VERSION_CONFLICT");
  }

  @Test
  void shouldReturnUnprocessableProblemForNegativePrice() {
    client.patch().uri("/api/admin/ingredients/FLTO/catalog")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"expectedVersion\":3,\"unitPrice\":-0.01}")
        .exchange()
        .expectStatus().isEqualTo(422)
        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code").isEqualTo("VALIDATION_FAILED");
  }

  private Ingredient ingredient(int stock, Long version) {
    Ingredient ingredient = new Ingredient("FLTO", "Flour Tortilla",
        Ingredient.Type.WRAP, new BigDecimal("0.50"), true, stock, 2);
    ingredient.setVersion(version);
    return ingredient;
  }
}
