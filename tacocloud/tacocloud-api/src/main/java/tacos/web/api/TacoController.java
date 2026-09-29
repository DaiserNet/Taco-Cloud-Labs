package tacos.web.api;

import java.util.stream.Collectors;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.api.dto.TacoClassification;
import tacos.api.dto.TacoCreateRequest;
import tacos.api.dto.TacoDesignValidationResponse;
import tacos.api.dto.TacoOfDayResponse;
import tacos.api.dto.TacoResponse;
import tacos.api.dto.TacoSearchRequest;
import tacos.api.dto.TacoSearchResponse;
import tacos.api.mapper.IngredientMapper;
import tacos.classification.TacoClassificationService;
import tacos.design.TacoDesignValidator;
import tacos.data.TacoRepository;
import tacos.search.TacoSearchService;
import tacos.recommendation.TacoOfDayService;

@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
public class TacoController {
  private final TacoRepository tacoRepo;
  private final TacoClassificationService classificationService;
  private final IngredientMapper ingredientMapper;
  private final TacoDesignValidator designValidator;
  private final TacoSearchService searchService;
  private final TacoOfDayService tacoOfDayService;

  public TacoController(TacoRepository tacoRepo,
      TacoClassificationService classificationService,
      IngredientMapper ingredientMapper, TacoDesignValidator designValidator,
      TacoSearchService searchService, TacoOfDayService tacoOfDayService) {
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService;
    this.ingredientMapper = ingredientMapper;
    this.designValidator = designValidator;
    this.searchService = searchService;
    this.tacoOfDayService = tacoOfDayService;
  }

  @GetMapping("/today")
  public Mono<TacoOfDayResponse> tacoOfDay() {
    return tacoOfDayService.today()
        .map(selection -> new TacoOfDayResponse(
            toResponse(selection.getTaco()), selection.getDate(),
            selection.getReason()));
  }

  @GetMapping
  public Mono<TacoSearchResponse> searchTacos(
      @ModelAttribute TacoSearchRequest request) {
    return searchService.search(request)
        .map(page -> new TacoSearchResponse(
            page.getContent().stream().map(this::toResponse)
                .collect(Collectors.toList()),
            page.getNumber(), page.getSize(), page.getTotalElements(),
            page.getTotalPages()));
  }

  @GetMapping(params="recent")
  public Flux<TacoResponse> recentTacos() {
    return tacoRepo.findAll().filter(Taco::isPublished).take(12)
        .concatMap(classificationService::resolveIngredients)
        .map(this::toResponse);
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<TacoResponse> postTaco(
      @Valid @RequestBody TacoCreateRequest request) {
    return designValidator.requireValid(request)
        .flatMap(tacoRepo::save)
        .map(this::toResponse);
  }

  @PostMapping(path = "/validate", consumes = "application/json")
  public Mono<TacoDesignValidationResponse> validateTaco(
      @Valid @RequestBody TacoCreateRequest request) {
    return designValidator.validate(request);
  }

  @GetMapping("/{id}")
  public Mono<TacoResponse> tacoById(@PathVariable("id") String id) {
    return findResolved(id).map(this::toResponse);
  }

  @GetMapping("/{id}/classification")
  public Mono<TacoClassification> classification(@PathVariable("id") String id) {
    return findResolved(id).map(classificationService::classify);
  }

  private Mono<Taco> findResolved(String id) {
    return tacoRepo.findById(id)
        .filter(Taco::isPublished)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
        .flatMap(classificationService::resolveIngredients);
  }

  private TacoResponse toResponse(Taco taco) {
    return new TacoResponse(taco.getId(), taco.getName(), taco.getCreatedAt(),
        taco.getIngredients().stream()
            .map(ingredientMapper::toResponse)
            .collect(Collectors.toList()),
        classificationService.classify(taco));
  }

}
