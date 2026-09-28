package tacos.design;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.api.dto.TacoCreateRequest;
import tacos.api.dto.TacoDesignValidationResponse;
import tacos.data.IngredientRepository;

@Service
public class TacoDesignValidator {
  private final IngredientRepository ingredientRepo;
  private final List<TacoDesignRule> rules;

  public TacoDesignValidator(IngredientRepository ingredientRepo,
      List<TacoDesignRule> rules) {
    this.ingredientRepo = ingredientRepo;
    this.rules = new ArrayList<>(rules);
  }

  public Mono<TacoDesignValidationResponse> validate(TacoCreateRequest request) {
    return inspect(toTaco(request)).map(Inspection::getValidation);
  }

  public Mono<Taco> requireValid(TacoCreateRequest request) {
    return requireValid(toTaco(request));
  }

  public Mono<Taco> requireValid(Taco taco) {
    return inspect(taco).flatMap(inspection -> inspection.validation.isValid()
        ? Mono.just(inspection.taco)
        : Mono.error(new TacoDesignException(inspection.validation)));
  }

  private Mono<Inspection> inspect(Taco taco) {
    return Mono.defer(() -> {
      List<Ingredient> references = taco.getIngredients() == null
          ? Collections.emptyList() : taco.getIngredients();
      List<String> ids = references.stream()
          .map(reference -> reference == null ? null : reference.getId())
          .collect(Collectors.toList());
      Set<String> uniqueIds = ids.stream().filter(StringUtils::hasText)
          .collect(Collectors.toCollection(LinkedHashSet::new));

      return Flux.fromIterable(uniqueIds)
          .concatMap(id -> ingredientRepo.findById(id)
              .map(ingredient -> new FoundIngredient(id, ingredient))
              .defaultIfEmpty(new FoundIngredient(id, null)))
          .collectMap(found -> found.id)
          .map(found -> {
            Map<String, Ingredient> catalog = new HashMap<>();
            found.forEach((id, result) -> {
              if (result.ingredient != null) {
                catalog.put(id, result.ingredient);
              }
            });
            return evaluate(taco, ids, catalog);
          });
    });
  }

  private Inspection evaluate(Taco taco, List<String> ids,
      Map<String, Ingredient> found) {
    TacoDesignContext context = new TacoDesignContext(ids, found);
    List<TacoDesignViolation> violations = rules.stream()
        .flatMap(rule -> rule.check(context).stream())
        .distinct()
        .sorted(Comparator.comparing(TacoDesignViolation::getCode)
            .thenComparing(TacoDesignViolation::getMessage))
        .collect(Collectors.toList());
    TacoDesignValidationResponse validation =
        new TacoDesignValidationResponse(violations.isEmpty(), violations);
    if (validation.isValid()) {
      taco.setIngredients(context.getResolvedIngredients());
    }
    return new Inspection(taco, validation);
  }

  private Taco toTaco(TacoCreateRequest request) {
    Taco taco = new Taco();
    taco.setName(request.getName());
    taco.setIngredients(request.getIngredients() == null
        ? Collections.emptyList()
        : request.getIngredients().stream()
            .map(reference -> reference == null ? null
                : new Ingredient(reference.getId(), null, null))
            .collect(Collectors.toList()));
    return taco;
  }

  private static class Inspection {
    private final Taco taco;
    private final TacoDesignValidationResponse validation;

    private Inspection(Taco taco, TacoDesignValidationResponse validation) {
      this.taco = taco;
      this.validation = validation;
    }

    private TacoDesignValidationResponse getValidation() {
      return validation;
    }
  }

  private static class FoundIngredient {
    private final String id;
    private final Ingredient ingredient;

    private FoundIngredient(String id, Ingredient ingredient) {
      this.id = id;
      this.ingredient = ingredient;
    }
  }
}
