package tacos.web.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.api.dto.TacoRankingResponse;
import tacos.api.mapper.IngredientMapper;
import tacos.classification.TacoClassificationService;
import tacos.data.TacoRepository;
import tacos.design.TacoDesignValidator;
import tacos.ratings.TacoRatingService;
import tacos.recommendation.TacoOfDayService;
import tacos.search.TacoSearchService;

class TacoRatingControllerTest {
  private final TacoRatingService service = mock(TacoRatingService.class);
  private final TacoRepository tacoRepo = mock(TacoRepository.class);

  @Test
  void shouldReturnTopFromSpecificRouteInsteadOfTacoIdRoute() {
    when(service.top(2)).thenReturn(Flux.just(new TacoRankingResponse(
        "A", "Taco A", new BigDecimal("4.50"), 2,
        Arrays.asList(0L, 0L, 0L, 1L, 1L))));

    client().get().uri("/api/tacos/top?limit=2").exchange()
        .expectStatus().isOk().expectBody()
        .jsonPath("$[0].tacoId").isEqualTo("A")
        .jsonPath("$[0].count").isEqualTo(2)
        .jsonPath("$[0].distribution[4]").isEqualTo(1);
    verify(tacoRepo, never()).findById("top");
  }

  @Test
  void shouldReturnNoContentAfterRatingCompletes() {
    when(service.rate(eq("A"), eq(5), isNull(Authentication.class)))
        .thenReturn(Mono.empty());

    client().put().uri("/api/tacos/A/rating")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"score\":5}").exchange()
        .expectStatus().isNoContent();
  }

  @Test
  void shouldRejectInvalidScoreBeforeCallingService() {
    client().put().uri("/api/tacos/A/rating")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"score\":0}").exchange()
        .expectStatus().isBadRequest();
    verify(service, never()).rate(eq("A"), eq(0), isNull(Authentication.class));
  }

  private WebTestClient client() {
    TacoController catalog = new TacoController(tacoRepo,
        new TacoClassificationService(null), new IngredientMapper(),
        mock(TacoDesignValidator.class), mock(TacoSearchService.class),
        mock(TacoOfDayService.class));
    return WebTestClient.bindToController(catalog,
        new TacoRatingController(service)).build();
  }
}
