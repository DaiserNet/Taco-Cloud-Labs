package tacos.web.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.api.mapper.IngredientMapper;
import tacos.classification.TacoClassificationService;
import tacos.data.TacoRepository;
import tacos.design.TacoDesignValidator;
import tacos.recommendation.TacoOfDayService;
import tacos.search.TacoSearchService;

class TacoOfDayControllerTest {
  private final TacoRepository tacoRepo = mock(TacoRepository.class);
  private final TacoOfDayService recommendation = mock(TacoOfDayService.class);

  @Test
  void shouldReturnTacoDateAndReasonFromTodayRoute() {
    Taco taco = new Taco();
    taco.setId("A");
    taco.setName("Taco A");
    taco.setIngredients(Collections.singletonList(
        new Ingredient("WRAP", "Wrap", Ingredient.Type.WRAP)));
    when(recommendation.today()).thenReturn(Mono.just(
        new TacoOfDayService.Selection(taco, LocalDate.of(1970, 1, 1),
            "Porque hoy es 1970-01-01 y este diseño está disponible.")));

    client().get().uri("/api/tacos/today").exchange()
        .expectStatus().isOk().expectBody()
        .jsonPath("$.taco.id").isEqualTo("A")
        .jsonPath("$.date").isEqualTo("1970-01-01")
        .jsonPath("$.reason").isEqualTo(
            "Porque hoy es 1970-01-01 y este diseño está disponible.");
    verify(tacoRepo, never()).findById(anyString());
  }

  @Test
  void shouldReturnNotFoundWhenNoTacoCanBeRecommended() {
    when(recommendation.today()).thenReturn(Mono.error(
        new ResponseStatusException(HttpStatus.NOT_FOUND,
            "No hay tacos válidos y disponibles para recomendar.")));

    client().get().uri("/api/tacos/today").exchange()
        .expectStatus().isNotFound();
  }

  private WebTestClient client() {
    TacoController controller = new TacoController(tacoRepo,
        new TacoClassificationService(null), new IngredientMapper(),
        mock(TacoDesignValidator.class), mock(TacoSearchService.class),
        recommendation);
    return WebTestClient.bindToController(controller).build();
  }
}
