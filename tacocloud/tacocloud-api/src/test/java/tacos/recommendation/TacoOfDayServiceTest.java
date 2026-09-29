package tacos.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Taco;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.design.TacoDesignTestSupport;

class TacoOfDayServiceTest {
  private final TacoRepository tacoRepo = mock(TacoRepository.class);
  private final IngredientRepository ingredientRepo = mock(IngredientRepository.class);
  private final Ingredient wrap = ingredient("WRAP", Ingredient.Type.WRAP);
  private final Ingredient veggieA = ingredient("VEGA", Ingredient.Type.VEGGIES);
  private final Ingredient veggieB = ingredient("VEGB", Ingredient.Type.VEGGIES);
  private final Ingredient veggieC = ingredient("VEGC", Ingredient.Type.VEGGIES);

  @Test
  void shouldReturnSameIdTwiceAndMovePredictablyOnNextDate() {
    prepareCatalog();
    when(tacoRepo.findAll()).thenReturn(Flux.just(
        taco("C", "VEGC"), taco("A", "VEGA"), taco("B", "VEGB")));

    TacoOfDayService firstDay = service("1970-01-01T12:00:00Z", "UTC");
    StepVerifier.create(firstDay.today())
        .assertNext(selection -> {
          assertEquals("A", selection.getTaco().getId());
          assertEquals(LocalDate.of(1970, 1, 1), selection.getDate());
          assertTrue(selection.getReason().contains("1970-01-01"));
        }).verifyComplete();
    StepVerifier.create(firstDay.today())
        .assertNext(selection -> assertEquals("A", selection.getTaco().getId()))
        .verifyComplete();

    StepVerifier.create(service("1970-01-02T12:00:00Z", "UTC").today())
        .assertNext(selection -> assertEquals("B", selection.getTaco().getId()))
        .verifyComplete();
    verify(tacoRepo, never()).save(any(Taco.class));
  }

  @Test
  void shouldIgnorePhysicalRepositoryOrder() {
    prepareCatalog();
    when(tacoRepo.findAll()).thenReturn(
        Flux.just(taco("C", "VEGC"), taco("B", "VEGB"), taco("A", "VEGA")),
        Flux.just(taco("A", "VEGA"), taco("C", "VEGC"), taco("B", "VEGB")));
    TacoOfDayService service = service("1970-01-02T12:00:00Z", "UTC");

    StepVerifier.create(service.today())
        .assertNext(selection -> assertEquals("B", selection.getTaco().getId()))
        .verifyComplete();
    StepVerifier.create(service.today())
        .assertNext(selection -> assertEquals("B", selection.getTaco().getId()))
        .verifyComplete();
  }

  @Test
  void shouldUseConfiguredZoneToDeriveTheDate() {
    prepareCatalog();
    when(tacoRepo.findAll()).thenReturn(Flux.just(
        taco("A", "VEGA"), taco("B", "VEGB"), taco("C", "VEGC")));

    StepVerifier.create(service("1970-01-01T01:00:00Z",
            "America/Mexico_City").today())
        .assertNext(selection -> {
          assertEquals(LocalDate.of(1969, 12, 31), selection.getDate());
          assertEquals("C", selection.getTaco().getId());
        }).verifyComplete();
  }

  @Test
  void shouldReturnNotFoundWhenThereAreNoCandidates() {
    when(tacoRepo.findAll()).thenReturn(Flux.empty());

    StepVerifier.create(service("1970-01-01T12:00:00Z", "UTC").today())
        .expectErrorSatisfies(error -> assertEquals(HttpStatus.NOT_FOUND,
            ((ResponseStatusException) error).getStatus()))
        .verify();
  }

  @Test
  void shouldExcludeInvalidDesignsUsingTc18Rules() {
    prepareCatalog();
    Taco invalid = new Taco();
    invalid.setId("X");
    invalid.setName("Invalid taco");
    invalid.setIngredients(Arrays.asList(reference("WRAP")));
    when(tacoRepo.findAll()).thenReturn(Flux.just(
        invalid, taco("A", "VEGA")));

    StepVerifier.create(service("1970-01-01T12:00:00Z", "UTC").today())
        .assertNext(selection -> assertEquals("A", selection.getTaco().getId()))
        .verifyComplete();
  }

  @Test
  void shouldStopRecommendingAnUnavailableTacoOnTheNextRequest() {
    prepareCatalog();
    when(tacoRepo.findAll()).thenReturn(Flux.just(
        taco("A", "VEGA"), taco("B", "VEGB"), taco("C", "VEGC")));
    TacoOfDayService service = service("1970-01-02T12:00:00Z", "UTC");
    StepVerifier.create(service.today())
        .assertNext(selection -> assertEquals("B", selection.getTaco().getId()))
        .verifyComplete();

    veggieB.setAvailable(false);
    StepVerifier.create(service.today())
        .assertNext(selection -> assertEquals("C", selection.getTaco().getId()))
        .verifyComplete();
    veggieB.setAvailable(true);
    veggieB.setStockOnHand(0);
    StepVerifier.create(service.today())
        .assertNext(selection -> assertEquals("C", selection.getTaco().getId()))
        .verifyComplete();
    verify(tacoRepo, never()).save(any(Taco.class));
  }

  private TacoOfDayService service(String instant, String zone) {
    Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    return new TacoOfDayService(tacoRepo,
        TacoDesignTestSupport.validator(ingredientRepo), clock,
        ZoneId.of(zone));
  }

  private void prepareCatalog() {
    when(ingredientRepo.findById("WRAP")).thenReturn(Mono.just(wrap));
    when(ingredientRepo.findById("VEGA")).thenReturn(Mono.just(veggieA));
    when(ingredientRepo.findById("VEGB")).thenReturn(Mono.just(veggieB));
    when(ingredientRepo.findById("VEGC")).thenReturn(Mono.just(veggieC));
  }

  private Ingredient ingredient(String id, Ingredient.Type type) {
    Ingredient ingredient = new Ingredient(id, id, type);
    ingredient.setAvailable(true);
    ingredient.setStockOnHand(10);
    return ingredient;
  }

  private Taco taco(String id, String veggieId) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Taco " + id);
    taco.setIngredients(Arrays.asList(reference("WRAP"), reference(veggieId)));
    return taco;
  }

  private Ingredient reference(String id) {
    return new Ingredient(id, null, null);
  }
}
