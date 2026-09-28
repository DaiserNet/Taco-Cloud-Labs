package tacos.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.Allergen;
import tacos.Ingredient;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.api.dto.TacoSearchRequest;
import tacos.classification.TacoClassificationService;
import tacos.data.IngredientRepository;

@Service
public class TacoSearchService {
  private static final int MAX_NAME_LENGTH = 80;
  private static final Pattern INGREDIENT_ID =
      Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private final ReactiveMongoTemplate mongo;
  private final IngredientRepository ingredientRepo;
  private final TacoClassificationService classificationService;
  private final int maxSize;

  public TacoSearchService(ReactiveMongoTemplate mongo,
      IngredientRepository ingredientRepo,
      TacoClassificationService classificationService,
      @Value("${tacocloud.taco-search.max-size:50}") int maxSize) {
    if (maxSize < 1) {
      throw new IllegalArgumentException("Maximum taco page size must be positive.");
    }
    this.mongo = mongo;
    this.ingredientRepo = ingredientRepo;
    this.classificationService = classificationService;
    this.maxSize = maxSize;
  }

  public Mono<Page<Taco>> search(TacoSearchRequest request) {
    String name = StringUtils.hasText(request.getName())
        ? request.getName().trim() : null;
    if (name != null && name.length() > MAX_NAME_LENGTH) {
      return badRequest("name must contain at most 80 characters.");
    }
    if (request.getIngredientId() != null
        && !INGREDIENT_ID.matcher(request.getIngredientId()).matches()) {
      return badRequest("ingredientId has an invalid format.");
    }
    if (request.getPage() < 0 || request.getSize() < 1
        || request.getSize() > maxSize
        || (long) request.getPage() * request.getSize() > Integer.MAX_VALUE) {
      return badRequest("page or size is outside the allowed range.");
    }
    Sort sort = parseSort(request.getSort());
    Mono<List<Ingredient>> catalog = request.getDiet() == null
        && request.getExcludeAllergen() == null && request.getSpice() == null
            ? Mono.just(Collections.emptyList())
            : ingredientRepo.findAll().collectList();

    return catalog.flatMap(ingredients -> {
      List<Criteria> filters = filters(request, name, ingredients);
      Query countQuery = query(filters);
      Query pageQuery = query(filters)
          .with(PageRequest.of(request.getPage(), request.getSize(), sort));
      Mono<List<Taco>> content = mongo.find(pageQuery, Taco.class)
          .concatMap(classificationService::resolveIngredients).collectList();
      return Mono.zip(content, mongo.count(countQuery, Taco.class))
          .map(result -> new PageImpl<>(result.getT1(),
              PageRequest.of(request.getPage(), request.getSize(), sort),
              result.getT2()));
    });
  }

  private List<Criteria> filters(TacoSearchRequest request, String name,
      List<Ingredient> ingredients) {
    List<Criteria> filters = new ArrayList<>();
    if (name != null) {
      filters.add(Criteria.where("name")
          .regex(Pattern.compile("^" + Pattern.quote(name))));
    }
    if (request.getIngredientId() != null) {
      filters.add(Criteria.where("ingredients._id")
          .is(request.getIngredientId()));
    }
    if (request.getDiet() != null) {
      Set<String> allowed = ingredients.stream()
          .filter(ingredient -> classificationService
              .supportsDiet(ingredient, request.getDiet()))
          .map(Ingredient::getId).collect(Collectors.toSet());
      filters.add(allIngredientsIn(allowed));
    }
    Allergen excluded = request.getExcludeAllergen();
    if (excluded != null) {
      Set<String> offending = ingredients.stream()
          .filter(ingredient -> ingredient.getAllergens() != null
              && ingredient.getAllergens().contains(excluded))
          .map(Ingredient::getId).collect(Collectors.toSet());
      filters.add(Criteria.where("ingredients._id").nin(offending));
    }
    if (request.getSpice() != null) {
      SpiceLevel requested = request.getSpice();
      Set<String> exact = ingredients.stream()
          .filter(ingredient -> classificationService.effectiveSpice(ingredient)
              == requested)
          .map(Ingredient::getId).collect(Collectors.toSet());
      if (requested == SpiceLevel.UNKNOWN) {
        filters.add(Criteria.where("ingredients._id").in(exact));
      } else {
        Set<String> allowed = ingredients.stream()
            .filter(ingredient -> {
              SpiceLevel level = classificationService.effectiveSpice(ingredient);
              return level != SpiceLevel.UNKNOWN
                  && level.ordinal() <= requested.ordinal();
            })
            .map(Ingredient::getId).collect(Collectors.toSet());
        filters.add(allIngredientsIn(allowed));
        if (requested != SpiceLevel.NONE) {
          filters.add(Criteria.where("ingredients._id").in(exact));
        }
      }
    }
    if (request.getDiet() != null || request.getSpice() != null) {
      filters.add(Criteria.where("ingredients.0").exists(true));
    }
    return filters;
  }

  private Criteria allIngredientsIn(Set<String> allowed) {
    return Criteria.where("ingredients").not()
        .elemMatch(Criteria.where("_id").nin(new HashSet<>(allowed)));
  }

  private Query query(List<Criteria> filters) {
    return filters.isEmpty() ? new Query()
        : new Query(new Criteria().andOperator(filters.toArray(new Criteria[0])));
  }

  private Sort parseSort(String value) {
    String[] parts = value == null ? new String[0] : value.split(",", -1);
    if (parts.length != 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "sort must be field,asc or field,desc.");
    }
    String field = parts[0].trim();
    if (!"createdAt".equals(field) && !"name".equals(field)
        && !"id".equals(field)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "sort field is not allowed.");
    }
    String direction = parts[1].trim();
    if (!"asc".equalsIgnoreCase(direction)
        && !"desc".equalsIgnoreCase(direction)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "sort direction must be asc or desc.");
    }
    Sort.Direction order = Sort.Direction.fromString(direction);
    return "id".equals(field) ? Sort.by(order, "id")
        : Sort.by(new Sort.Order(order, field),
            new Sort.Order(order, "id"));
  }

  private <T> Mono<T> badRequest(String reason) {
    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, reason));
  }
}
