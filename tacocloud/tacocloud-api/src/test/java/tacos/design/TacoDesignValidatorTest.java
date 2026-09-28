package tacos.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import tacos.Ingredient;
import tacos.Taco;
import tacos.data.IngredientRepository;

class TacoDesignValidatorTest {

  @Test
  void shouldComposeAllViolationsAndReadRepeatedIdOnlyOnce() {
    IngredientRepository repo = mock(IngredientRepository.class);
    when(repo.findById("WRAP")).thenReturn(Mono.just(
        ingredient("WRAP", Ingredient.Type.WRAP, true)));
    when(repo.findById("PROT")).thenReturn(Mono.just(
        ingredient("PROT", Ingredient.Type.PROTEIN, false)));
    when(repo.findById("UNKNOWN")).thenReturn(Mono.empty());
    Taco taco = taco("WRAP", "WRAP", "PROT", "UNKNOWN");

    StepVerifier.create(TacoDesignTestSupport.validator(repo).requireValid(taco))
        .expectErrorSatisfies(error -> {
          TacoDesignException failure = (TacoDesignException) error;
          List<String> codes = failure.getValidation().getViolations().stream()
              .map(TacoDesignViolation::getCode).collect(Collectors.toList());
          assertEquals(Arrays.asList("TACO_BASE_COUNT",
              "TACO_DUPLICATE_INGREDIENT", "TACO_INGREDIENT_UNAVAILABLE",
              "TACO_INGREDIENT_UNKNOWN", "TACO_PROTEIN_NEEDS_SAUCE"), codes);
        }).verify();
    verify(repo, times(1)).findById("WRAP");
  }

  @Test
  void shouldResolveBowlDesignBeforeReturningIt() {
    IngredientRepository repo = mock(IngredientRepository.class);
    Ingredient bowl = ingredient("BOWL", Ingredient.Type.BOWL, true);
    Ingredient veggies = ingredient("VEG", Ingredient.Type.VEGGIES, true);
    PublisherProbe<Ingredient> bowlLookup = PublisherProbe.of(Mono.just(bowl));
    when(repo.findById("BOWL")).thenReturn(bowlLookup.mono());
    when(repo.findById("VEG")).thenReturn(Mono.just(veggies));

    StepVerifier.create(TacoDesignTestSupport.validator(repo)
            .requireValid(taco("BOWL", "VEG")))
        .assertNext(valid -> assertEquals(Arrays.asList(bowl, veggies),
            valid.getIngredients()))
        .verifyComplete();
    bowlLookup.assertWasSubscribed();
    bowlLookup.assertWasRequested();
  }

  @Test
  void shouldAcceptNewInjectedRuleWithoutChangingValidator() {
    IngredientRepository repo = mock(IngredientRepository.class);
    when(repo.findById("WRAP")).thenReturn(Mono.just(
        ingredient("WRAP", Ingredient.Type.WRAP, true)));
    when(repo.findById("VEG")).thenReturn(Mono.just(
        ingredient("VEG", Ingredient.Type.VEGGIES, true)));
    TacoDesignRule fake = context -> Collections.singletonList(
        new TacoDesignViolation("FAKE_RULE", "Injected rule ran."));
    TacoDesignValidator validator = new TacoDesignValidator(repo,
        Collections.singletonList(fake));

    StepVerifier.create(validator.requireValid(taco("WRAP", "VEG")))
        .expectErrorSatisfies(error -> {
          TacoDesignException failure = (TacoDesignException) error;
          assertEquals("FAKE_RULE",
              failure.getValidation().getViolations().get(0).getCode());
        }).verify();
  }

  private Taco taco(String... ids) {
    Taco taco = new Taco();
    taco.setName("Designed taco");
    taco.setIngredients(Arrays.stream(ids)
        .map(id -> new Ingredient(id, null, null))
        .collect(Collectors.toList()));
    return taco;
  }

  private Ingredient ingredient(String id, Ingredient.Type type,
      boolean available) {
    Ingredient ingredient = new Ingredient(id, id, type);
    ingredient.setAvailable(available);
    ingredient.setStockOnHand(10);
    return ingredient;
  }
}
