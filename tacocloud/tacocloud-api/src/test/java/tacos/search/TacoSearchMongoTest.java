package tacos.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.api.dto.TacoSearchRequest;
import tacos.classification.TacoClassificationService;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;

@SpringBootTest(classes = TacoSearchMongoTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.config.name=tc19-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc19-test",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.mongodb.embedded.version=3.5.5"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TacoSearchMongoTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @EnableReactiveMongoRepositories(basePackageClasses = TacoRepository.class)
  @Import({TacoSearchService.class, TacoClassificationService.class})
  static class TestApplication {
  }

  @Autowired private TacoSearchService searchService;
  @Autowired private TacoRepository tacoRepo;
  @Autowired private IngredientRepository ingredientRepo;
  @Autowired private ReactiveMongoTemplate mongo;
  @Autowired private TacoClassificationService classificationService;

  @BeforeEach
  void setUp() {
    Ingredient wrap = ingredient("W1", Ingredient.Type.WRAP,
        EnumSet.of(DietaryTag.VEGAN, DietaryTag.GLUTEN_FREE),
        EnumSet.noneOf(Allergen.class), SpiceLevel.NONE);
    Ingredient veggies = ingredient("V1", Ingredient.Type.VEGGIES,
        EnumSet.of(DietaryTag.VEGAN, DietaryTag.GLUTEN_FREE),
        EnumSet.noneOf(Allergen.class), SpiceLevel.MILD);
    Ingredient spicy = ingredient("S1", Ingredient.Type.SAUCE,
        EnumSet.of(DietaryTag.VEGAN, DietaryTag.GLUTEN_FREE),
        EnumSet.of(Allergen.PEANUTS), SpiceLevel.HOT);
    Ingredient protein = ingredient("P1", Ingredient.Type.PROTEIN,
        EnumSet.of(DietaryTag.VEGETARIAN), EnumSet.of(Allergen.MILK),
        SpiceLevel.MEDIUM);
    Ingredient sauce = ingredient("S2", Ingredient.Type.SAUCE,
        EnumSet.of(DietaryTag.VEGETARIAN), EnumSet.noneOf(Allergen.class),
        SpiceLevel.NONE);
    Ingredient unknown = ingredient("U1", Ingredient.Type.SAUCE,
        EnumSet.noneOf(DietaryTag.class), EnumSet.noneOf(Allergen.class),
        SpiceLevel.UNKNOWN);
    StepVerifier.create(tacoRepo.deleteAll().then(ingredientRepo.deleteAll())
        .thenMany(ingredientRepo.saveAll(Arrays.asList(
            wrap, veggies, spicy, protein, sauce, unknown)))
        .thenMany(Flux.fromIterable(Arrays.asList(
            taco("A", "Alpha vegan mild", 1000, wrap, veggies),
            taco("D", "Alpha vegan mild", 1000, wrap, veggies),
            taco("B", "Alpha spicy", 2000, wrap, spicy),
            taco("C", "Beta protein", 3000, wrap, protein, sauce),
            taco("E", "Gamma unknown", 4000, wrap, unknown)))
            .concatMap(tacoRepo::save)).then()).verifyComplete();
  }

  @Test
  void shouldReturnFirstPageWhenNoFiltersAreProvided() {
    StepVerifier.create(searchService.search(new TacoSearchRequest()))
        .assertNext(page -> {
          assertEquals(Arrays.asList("E", "C", "B", "D", "A"), ids(page.getContent()));
          assertEquals(5, page.getTotalElements());
          assertEquals(0, page.getNumber());
        }).verifyComplete();
  }

  @Test
  void shouldExcludeUnpublishedTacosFromPublicSearch() {
    Taco draft = taco("H", "Hidden taco", 5000,
        ingredient("W1", Ingredient.Type.WRAP,
            EnumSet.of(DietaryTag.VEGAN), EnumSet.noneOf(Allergen.class),
            SpiceLevel.NONE));
    draft.setPublished(false);
    StepVerifier.create(tacoRepo.save(draft)).expectNextCount(1)
        .verifyComplete();

    assertIds(new TacoSearchRequest(), "E", "C", "B", "D", "A");
    TacoSearchRequest request = new TacoSearchRequest();
    request.setName("Hidden");
    assertIds(request);
  }

  @Test
  void shouldApplyEachFilterUsingCurrentIngredientClassification() {
    TacoSearchRequest request = new TacoSearchRequest();
    request.setName("Alpha");
    assertIds(request, "B", "D", "A");
    request.setName(null);
    request.setIngredientId("P1");
    assertIds(request, "C");
    request.setIngredientId(null);
    request.setDiet(DietaryTag.VEGAN);
    assertIds(request, "B", "D", "A");
    request.setDiet(null);
    request.setExcludeAllergen(Allergen.PEANUTS);
    assertIds(request, "E", "C", "D", "A");
    request.setExcludeAllergen(null);
    request.setSpice(SpiceLevel.MILD);
    assertIds(request, "D", "A");
    request.setSpice(SpiceLevel.UNKNOWN);
    assertIds(request, "E");
  }

  @Test
  void shouldIntersectAllFiltersAndMatchTc17Classification() {
    TacoSearchRequest request = new TacoSearchRequest();
    request.setName("Alpha");
    request.setIngredientId("W1");
    request.setDiet(DietaryTag.VEGAN);
    request.setExcludeAllergen(Allergen.PEANUTS);
    request.setSpice(SpiceLevel.MILD);
    StepVerifier.create(searchService.search(request))
        .assertNext(page -> {
          assertEquals(Arrays.asList("D", "A"), ids(page.getContent()));
          page.getContent().forEach(taco -> assertTrue(classificationService
              .classify(taco).getDietaryTags().contains(DietaryTag.VEGAN)));
        }).verifyComplete();
  }

  @Test
  void shouldUseIdToBreakTiesAcrossPages() {
    TacoSearchRequest request = new TacoSearchRequest();
    request.setSize(1);
    request.setSort("createdAt,asc");
    assertIds(request, "A");
    request.setPage(1);
    assertIds(request, "D");
    request.setPage(2);
    assertIds(request, "B");
  }

  @Test
  void shouldRejectUnsafeLimitsSortAndRegexText() {
    TacoSearchRequest request = new TacoSearchRequest();
    request.setSize(51);
    expectBadRequest(request);
    request.setSize(0);
    expectBadRequest(request);
    request.setSize(20);
    request.setPage(-1);
    expectBadRequest(request);
    request.setPage(0);
    request.setSort("ingredients,desc");
    org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
        () -> searchService.search(request));
    request.setSort("createdAt,desc");
    request.setName("Alpha.*");
    assertIds(request);
    request.setName(String.join("", java.util.Collections.nCopies(81, "x")));
    expectBadRequest(request);
  }

  @Test
  void shouldCreateSearchIndexesInRealMongo() {
    StepVerifier.create(mongo.indexOps(Taco.class).getIndexInfo()
        .map(index -> index.getName()).collectList())
        .assertNext(names -> {
          assertTrue(names.contains("taco_created_id"));
          assertTrue(names.contains("taco_name_id"));
          assertTrue(names.contains("taco_ingredient_created_id"));
        }).verifyComplete();
  }

  @Test
  void shouldUseUpdatedIngredientMetadataRatherThanEmbeddedSnapshot() {
    StepVerifier.create(ingredientRepo.findById("U1")
        .flatMap(ingredient -> {
          ingredient.setDietaryTags(EnumSet.of(DietaryTag.VEGAN));
          return ingredientRepo.save(ingredient);
        })).expectNextCount(1).verifyComplete();
    TacoSearchRequest request = new TacoSearchRequest();
    request.setDiet(DietaryTag.VEGAN);
    assertIds(request, "E", "B", "D", "A");
  }

  private void assertIds(TacoSearchRequest request, String... expected) {
    StepVerifier.create(searchService.search(request))
        .assertNext(page -> assertEquals(Arrays.asList(expected), ids(page.getContent())))
        .verifyComplete();
  }

  private void expectBadRequest(TacoSearchRequest request) {
    StepVerifier.create(searchService.search(request))
        .expectError(ResponseStatusException.class).verify();
  }

  private List<String> ids(List<Taco> tacos) {
    return tacos.stream().map(Taco::getId).collect(Collectors.toList());
  }

  private Ingredient ingredient(String id, Ingredient.Type type,
      java.util.Set<DietaryTag> diet, java.util.Set<Allergen> allergens,
      SpiceLevel spice) {
    Ingredient ingredient = new Ingredient(id, id, type);
    ingredient.setAvailable(true);
    ingredient.setStockOnHand(10);
    ingredient.setDietaryTags(diet);
    ingredient.setAllergens(allergens);
    ingredient.setSpiceLevel(spice);
    return ingredient;
  }

  private Taco taco(String id, String name, long date, Ingredient... ingredients) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName(name);
    taco.setCreatedAt(new Date(date));
    taco.setIngredients(Arrays.asList(ingredients));
    return taco;
  }
}
