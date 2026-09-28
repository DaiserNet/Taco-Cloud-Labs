package tacos.classification;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.api.dto.TacoClassification;
import tacos.data.IngredientRepository;

@Service
public class TacoClassificationService {

  public static final String DISCLAIMER =
      "Academic ingredient metadata does not replace real cross-contact controls.";

  private final IngredientRepository ingredientRepo;

  public TacoClassificationService(IngredientRepository ingredientRepo) {
    this.ingredientRepo = ingredientRepo;
  }

  public Mono<Taco> resolveIngredients(Taco taco) {
    List<Ingredient> references = taco.getIngredients() == null
        ? Collections.emptyList() : taco.getIngredients();
    return Flux.fromIterable(references)
        .concatMap(reference -> {
          String id = reference == null ? null : reference.getId();
          if (!StringUtils.hasText(id)) {
            return Mono.<Ingredient>error(new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Every taco ingredient must have an ID."));
          }
          return ingredientRepo.findById(id)
              .switchIfEmpty(Mono.error(new ResponseStatusException(
                  HttpStatus.NOT_FOUND, "Unknown ingredient: " + id)));
        })
        .collectList()
        .map(ingredients -> {
          taco.setIngredients(ingredients);
          return taco;
        });
  }

  public TacoClassification classify(Taco taco) {
    List<Ingredient> ingredients = taco.getIngredients() == null
        ? Collections.emptyList() : taco.getIngredients();
    boolean vegan = !ingredients.isEmpty();
    boolean vegetarian = !ingredients.isEmpty();
    boolean glutenFree = !ingredients.isEmpty();
    Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);
    SpiceLevel spice = ingredients.isEmpty()
        ? SpiceLevel.UNKNOWN : SpiceLevel.NONE;

    for (Ingredient ingredient : ingredients) {
      Set<Allergen> declaredAllergens = ingredient.getAllergens() == null
          ? Collections.emptySet() : ingredient.getAllergens();
      vegan &= supportsDiet(ingredient, DietaryTag.VEGAN);
      vegetarian &= supportsDiet(ingredient, DietaryTag.VEGETARIAN);
      glutenFree &= supportsDiet(ingredient, DietaryTag.GLUTEN_FREE);
      allergens.addAll(declaredAllergens);

      SpiceLevel level = effectiveSpice(ingredient);
      if (level == SpiceLevel.UNKNOWN) {
        spice = SpiceLevel.UNKNOWN;
      } else if (spice != SpiceLevel.UNKNOWN
          && level.ordinal() > spice.ordinal()) {
        spice = level;
      }
    }

    Set<DietaryTag> resultTags = EnumSet.noneOf(DietaryTag.class);
    if (vegan) {
      resultTags.add(DietaryTag.VEGAN);
    }
    if (vegetarian) {
      resultTags.add(DietaryTag.VEGETARIAN);
    }
    if (glutenFree) {
      resultTags.add(DietaryTag.GLUTEN_FREE);
    }
    return new TacoClassification(
        taco.getId(), resultTags, allergens, spice, DISCLAIMER);
  }

  public boolean supportsDiet(Ingredient ingredient, DietaryTag tag) {
    Set<DietaryTag> tags = ingredient.getDietaryTags() == null
        ? Collections.emptySet() : ingredient.getDietaryTags();
    Set<Allergen> allergens = ingredient.getAllergens() == null
        ? Collections.emptySet() : ingredient.getAllergens();
    switch (tag) {
      case VEGAN:
        return tags.contains(DietaryTag.VEGAN);
      case VEGETARIAN:
        return tags.contains(DietaryTag.VEGAN)
            || tags.contains(DietaryTag.VEGETARIAN);
      case GLUTEN_FREE:
        return tags.contains(DietaryTag.GLUTEN_FREE)
            && !allergens.contains(Allergen.GLUTEN);
      default:
        return false;
    }
  }

  public SpiceLevel effectiveSpice(Ingredient ingredient) {
    return ingredient.getSpiceLevel() == null
        ? SpiceLevel.UNKNOWN : ingredient.getSpiceLevel();
  }
}
