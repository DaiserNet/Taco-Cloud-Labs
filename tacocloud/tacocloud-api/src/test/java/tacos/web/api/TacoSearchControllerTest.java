package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.api.dto.TacoSearchRequest;
import tacos.api.mapper.IngredientMapper;
import tacos.classification.TacoClassificationService;
import tacos.design.TacoDesignValidator;
import tacos.data.TacoRepository;
import tacos.search.TacoSearchService;

class TacoSearchControllerTest {
  @Test
  void shouldBindSearchFiltersAndReturnPaginationMetadata() {
    TacoSearchService search = mock(TacoSearchService.class);
    Taco taco = new Taco();
    taco.setId("A");
    taco.setName("Alpha taco");
    taco.setIngredients(Collections.singletonList(
        new Ingredient("W1", "Wrap", Ingredient.Type.WRAP)));
    when(search.search(any(TacoSearchRequest.class))).thenReturn(Mono.just(
        new PageImpl<>(Collections.singletonList(taco),
            PageRequest.of(0, 1), 2)));
    TacoController controller = new TacoController(mock(TacoRepository.class),
        new TacoClassificationService(null), new IngredientMapper(),
        mock(TacoDesignValidator.class), search, null);

    WebTestClient.bindToController(controller).build().get()
        .uri("/api/tacos?name=Alpha&ingredientId=W1&diet=VEGAN"
            + "&excludeAllergen=MILK&spice=NONE&page=0&size=1"
            + "&sort=name,asc")
        .exchange().expectStatus().isOk().expectBody()
        .jsonPath("$.content[0].id").isEqualTo("A")
        .jsonPath("$.page").isEqualTo(0)
        .jsonPath("$.size").isEqualTo(1)
        .jsonPath("$.totalElements").isEqualTo(2)
        .jsonPath("$.totalPages").isEqualTo(2);

    ArgumentCaptor<TacoSearchRequest> request =
        ArgumentCaptor.forClass(TacoSearchRequest.class);
    verify(search).search(request.capture());
    assertEquals("Alpha", request.getValue().getName());
    assertEquals("W1", request.getValue().getIngredientId());
    assertEquals(DietaryTag.VEGAN, request.getValue().getDiet());
    assertEquals(Allergen.MILK, request.getValue().getExcludeAllergen());
    assertEquals(SpiceLevel.NONE, request.getValue().getSpice());
    assertEquals("name,asc", request.getValue().getSort());
  }
}
