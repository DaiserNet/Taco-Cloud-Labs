package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.api.mapper.IngredientMapper;
import tacos.api.error.ApiExceptionHandler;
import tacos.classification.TacoClassificationService;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.design.TacoDesignTestSupport;

public class TacoControllerTest {

  @Test
  public void shouldReturnRecentTacos() {
    Taco[] tacos = {
        testTaco(1L), testTaco(2L),
        testTaco(3L), testTaco(4L),
        testTaco(5L), testTaco(6L),
        testTaco(7L), testTaco(8L),
        testTaco(9L), testTaco(10L),
        testTaco(11L), testTaco(12L),
        testTaco(13L), testTaco(14L),
        testTaco(15L), testTaco(16L)};
    Flux<Taco> tacoFlux = Flux.just(tacos);

    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = ingredients();
    when(tacoRepo.findAll()).thenReturn(tacoFlux);

    WebTestClient testClient = WebTestClient.bindToController(
        controller(tacoRepo, ingredientRepo))
        .build();

    testClient.get().uri("/api/tacos?recent")
      .exchange()
      .expectStatus().isOk()
      .expectBody()
        .jsonPath("$").isArray()
        .jsonPath("$").isNotEmpty()
        .jsonPath("$[0].id").isEqualTo(tacos[0].getId().toString())
        .jsonPath("$[0].name").isEqualTo("Taco 1")
        .jsonPath("$[1].id").isEqualTo(tacos[1].getId().toString())
        .jsonPath("$[1].name").isEqualTo("Taco 2")
        .jsonPath("$[11].id").isEqualTo(tacos[11].getId().toString())
        .jsonPath("$[11].name").isEqualTo("Taco 12")
        .jsonPath("$[0].classification.spiceLevel").isEqualTo("NONE")
        .jsonPath("$[12]").doesNotExist();
  }

  @Test
  public void shouldSaveATaco() {
    TacoRepository tacoRepo = Mockito.mock(
                TacoRepository.class);
    IngredientRepository ingredientRepo = ingredients();
    when(tacoRepo.save(any())).thenAnswer(invocation -> {
      Taco saved = invocation.getArgument(0);
      saved.setId("TESTID");
      return Mono.just(saved);
    });

    WebTestClient testClient = WebTestClient.bindToController(
        controller(tacoRepo, ingredientRepo)).build();

    testClient.post()
        .uri("/api/tacos")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Test taco\",\"ingredients\":["
            + "{\"id\":\"INGA\"},{\"id\":\"INGB\"},{\"id\":\"INGC\"}]}")
      .exchange()
      .expectStatus().isCreated()
      .expectBody()
        .jsonPath("$.id").isEqualTo("TESTID")
        .jsonPath("$.ingredients[0].name").isEqualTo("Ingredient A")
        .jsonPath("$.classification.dietaryTags[0]").isEqualTo("VEGETARIAN");
  }

  @Test
  void shouldClassifyCatalogTacoFromTrustedIngredients() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = ingredients();
    Taco taco = testTaco(1L);
    taco.getIngredients().get(0).setDietaryTags(EnumSet.allOf(DietaryTag.class));
    when(tacoRepo.findById("1")).thenReturn(Mono.just(taco));

    WebTestClient.bindToController(controller(tacoRepo, ingredientRepo))
        .build().get().uri("/api/tacos/1/classification")
        .exchange().expectStatus().isOk().expectBody()
        .jsonPath("$.dietaryTags[0]").isEqualTo("VEGETARIAN")
        .jsonPath("$.dietaryTags[1]").doesNotExist()
        .jsonPath("$.allergens[0]").isEqualTo("GLUTEN")
        .jsonPath("$.spiceLevel").isEqualTo("NONE")
        .jsonPath("$.disclaimer").exists();
  }

  @Test
  void shouldRejectForgedClassificationOnCatalogPost() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = ingredients();
    WebTestClient client = WebTestClient.bindToController(
        controller(tacoRepo, ingredientRepo)).build();

    client.post().uri("/api/tacos").contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Forged taco\",\"classification\":{} ,"
            + "\"ingredients\":[{\"id\":\"INGA\"}]}")
        .exchange().expectStatus().isBadRequest();
    client.post().uri("/api/tacos").contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Forged taco\",\"ingredients\":[{"
            + "\"id\":\"INGA\",\"dietaryTags\":[\"VEGAN\"]}]}")
        .exchange().expectStatus().isBadRequest();
    client.post().uri("/api/tacos").contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Invalid taco\",\"ingredients\":[null]}")
        .exchange().expectStatus().isBadRequest();
    verify(tacoRepo, never()).save(any());
  }

  @Test
  void shouldValidateDesignWithoutSavingIt() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = ingredients();
    WebTestClient client = WebTestClient.bindToController(
        controller(tacoRepo, ingredientRepo)).build();

    client.post().uri("/api/tacos/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Valid taco\",\"ingredients\":["
            + "{\"id\":\"INGA\"},{\"id\":\"INGB\"},{\"id\":\"INGC\"}]}")
        .exchange().expectStatus().isOk().expectBody()
        .jsonPath("$.valid").isEqualTo(true)
        .jsonPath("$.violations").isArray()
        .jsonPath("$.violations[0]").doesNotExist();
    verify(tacoRepo, never()).save(any());
  }

  @Test
  void shouldReturnAllDesignViolationsAndRejectCreation() throws Exception {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = ingredients();
    WebTestClient client = WebTestClient.bindToController(
        controller(tacoRepo, ingredientRepo)).build();
    String body = "{\"name\":\"Wrong taco\",\"ingredients\":["
        + "{\"id\":\"INGA\"},{\"id\":\"INGA\"},{\"id\":\"INGB\"}]}";

    client.post().uri("/api/tacos/validate")
        .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        .exchange().expectStatus().isOk().expectBody()
        .jsonPath("$.valid").isEqualTo(false)
        .jsonPath("$.violations[0].code").isEqualTo("TACO_BASE_COUNT")
        .jsonPath("$.violations[1].code").isEqualTo("TACO_DUPLICATE_INGREDIENT")
        .jsonPath("$.violations[2].code").isEqualTo("TACO_PROTEIN_NEEDS_SAUCE");
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
        controller(tacoRepo, ingredientRepo))
        .setControllerAdvice(new ApiExceptionHandler()).build();
    MvcResult pending = mvc.perform(post("/api/tacos")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(request().asyncStarted()).andReturn();
    mvc.perform(asyncDispatch(pending))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TACO_DESIGN_INVALID"))
        .andExpect(jsonPath("$.violations[2].code")
            .value("TACO_PROTEIN_NEEDS_SAUCE"));
    verify(tacoRepo, never()).save(any());
  }

  private TacoController controller(
      TacoRepository tacoRepo, IngredientRepository ingredientRepo) {
    return new TacoController(tacoRepo,
        new TacoClassificationService(ingredientRepo), new IngredientMapper(),
        TacoDesignTestSupport.validator(ingredientRepo), null);
  }

  private IngredientRepository ingredients() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    Ingredient wrap = new Ingredient("INGA", "Ingredient A", Type.WRAP);
    wrap.setAvailable(true);
    wrap.setStockOnHand(20);
    wrap.setDietaryTags(EnumSet.of(DietaryTag.VEGAN,
        DietaryTag.VEGETARIAN));
    wrap.setAllergens(EnumSet.of(Allergen.GLUTEN));
    wrap.setSpiceLevel(SpiceLevel.NONE);
    Ingredient protein = new Ingredient("INGB", "Ingredient B", Type.PROTEIN);
    protein.setAvailable(true);
    protein.setStockOnHand(20);
    protein.setDietaryTags(EnumSet.of(DietaryTag.VEGETARIAN));
    protein.setSpiceLevel(SpiceLevel.NONE);
    when(repo.findById("INGA")).thenReturn(Mono.just(wrap));
    when(repo.findById("INGB")).thenReturn(Mono.just(protein));
    Ingredient sauce = new Ingredient("INGC", "Ingredient C", Type.SAUCE);
    sauce.setAvailable(true);
    sauce.setStockOnHand(20);
    sauce.setDietaryTags(EnumSet.of(DietaryTag.VEGETARIAN));
    sauce.setSpiceLevel(SpiceLevel.NONE);
    when(repo.findById("INGC")).thenReturn(Mono.just(sauce));
    return repo;
  }

  private Taco testTaco(Long number) {
    Taco taco = new Taco();
    taco.setId(number != null ? number.toString(): "TESTID");
    taco.setName("Taco " + number);
    List<Ingredient> ingredients = new ArrayList<>();
    ingredients.add(
        new Ingredient("INGA", "Ingredient A", Type.WRAP));
    ingredients.add(
        new Ingredient("INGB", "Ingredient B", Type.PROTEIN));
    taco.setIngredients(ingredients);
    return taco;
  }
}
