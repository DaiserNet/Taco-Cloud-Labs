package tacos.web.api;

import java.math.BigDecimal;
import java.util.EnumSet;

import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;
import tacos.api.dto.IngredientRequest;
import tacos.api.dto.IngredientResponse;
import tacos.api.error.ApiExceptionHandler;
import tacos.api.mapper.IngredientMapper;
import tacos.data.IngredientRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class IngredientControllerTest {

    private WebTestClient testClient;

    private IngredientRepository repo; 

    private IngredientController controller;

    @BeforeEach
    public void setUp() {
        repo = Mockito.mock(IngredientRepository.class);
        controller = new IngredientController(repo, new IngredientMapper());
        testClient = MockMvcWebTestClient.bindToController(controller)
            .controllerAdvice(new ApiExceptionHandler())
            .build();
    }

    // Put test case
    @Test
    public void shouldUpdateIngredient() {
        Mockito.when(repo.findById("FLTO"))
            .thenReturn(Mono.just(new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP)));
        Mockito.when(repo.save(Mockito.any(Ingredient.class)))
            .thenReturn(Mono.just(new Ingredient("FLTO", "New Flour Tortilla", Ingredient.Type.WRAP)));    
        testClient.put().uri("/api/ingredients/FLTO")
            .bodyValue(request("FLTO", "New Flour Tortilla", Ingredient.Type.WRAP))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
                .jsonPath("$.id").isEqualTo("FLTO")
                .jsonPath("$.name").isEqualTo("New Flour Tortilla")
                .jsonPath("$.type").isEqualTo("WRAP");
        
        Mockito.verify(repo, Mockito.times(1)).findById("FLTO");
        
        Mockito.verify(repo, Mockito.times(1)).save(Mockito.argThat(ingredient -> 
            ingredient.getId().equals("FLTO") &&
            ingredient.getName().equals("New Flour Tortilla") &&
            ingredient.getType() == Ingredient.Type.WRAP
        ));
        

    }

    @Test
    public void shouldReturnBadRequestForMismatchedId() {
        testClient.put().uri("/api/ingredients/FLTO")
            .bodyValue(request("LFTO", "Lettuce", Ingredient.Type.VEGGIES))
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody().jsonPath("$.code").isEqualTo("MALFORMED_REQUEST");
        Mockito.verify(repo, Mockito.times(0)).save(Mockito.any(Ingredient.class));
    }

    @Test
    public void shouldReturnNotFoundForNonexistentIngredient() {
        Mockito.when(repo.findById("LFTO")).thenReturn(Mono.empty());
        testClient.put().uri("/api/ingredients/LFTO")
            .bodyValue(request("LFTO", "Lettuce", Ingredient.Type.VEGGIES))
            .exchange()
            .expectStatus().isNotFound()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        Mockito.verify(repo, Mockito.times(0)).save(Mockito.any(Ingredient.class));
    }

    @Test
    public void shouldEmitAndComplete() {
        Ingredient ingredient = new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP);
        
        PublisherProbe<Ingredient> saveProbe = PublisherProbe.of(Mono.just(ingredient));
        
        Mockito.when(repo.findById("FLTO")).thenReturn(Mono.just(ingredient));
        Mockito.when(repo.save(Mockito.any(Ingredient.class))).thenReturn(saveProbe.mono());

        Mono<ResponseEntity<IngredientResponse>> responseMono = controller.updateIngredient(
            "FLTO", request("FLTO", "Flour Tortilla", Ingredient.Type.WRAP));


        StepVerifier.create(responseMono)
            .expectNextMatches(response -> response.getStatusCode().is2xxSuccessful()
                && response.getBody().equals(new IngredientResponse(
                    "FLTO", "Flour Tortilla", Ingredient.Type.WRAP,
                    new BigDecimal("0.00"), false)))
            .verifyComplete();

        saveProbe.assertWasSubscribed();
        saveProbe.assertWasRequested();
        saveProbe.assertWasNotCancelled();
    }

    // Delete test case
    @Test
    public void shouldDeleteIngredient() {
        Mockito.when(repo.findById("FLTO"))
            .thenReturn(Mono.just(new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP)));
        Mockito.when(repo.delete(Mockito.any(Ingredient.class)))
            .thenReturn(Mono.empty());
        testClient.delete().uri("/api/ingredients/FLTO")
            .exchange()
            .expectStatus().isNoContent();
        Mockito.verify(repo, Mockito.times(1)).delete(Mockito.any(Ingredient.class));
        
    }

    @Test
    public void shouldReturnNotFoundForDeleteNonexistentIngredient() {
        Mockito.when(repo.findById("FLTO"))
            .thenReturn(Mono.empty());
        testClient.delete().uri("/api/ingredients/FLTO")
            .exchange()
            .expectStatus().isNotFound()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        Mockito.verify(repo, Mockito.times(0)).delete(Mockito.any(Ingredient.class));
    }

    // Post test case
    @Test
    public void shouldCreateIngredient() {
        Ingredient ingredient = new Ingredient("PICK", "Pickle", Ingredient.Type.VEGGIES);

        Mockito.when(repo.save(Mockito.any(Ingredient.class)))
            .thenReturn(Mono.just(ingredient));

        testClient.post().uri("/api/ingredients")
            .bodyValue(request("PICK", "Pickle", Ingredient.Type.VEGGIES))
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", ".*/api/ingredients/PICK")
            .expectBody()
                .jsonPath("$.id").isEqualTo("PICK")
                .jsonPath("$.name").isEqualTo("Pickle")
                .jsonPath("$.type").isEqualTo("VEGGIES");
        Mockito.verify(repo, Mockito.times(1)).save(Mockito.argThat(i -> 
            i.getId().equals("PICK") &&
            i.getName().equals("Pickle") &&
            i.getType() == Ingredient.Type.VEGGIES
        ));
    }

    @Test
    public void shouldCreateIngredientWithVersionedLocation() {
        Ingredient ingredient = new Ingredient("PICK", "Pickle",
            Ingredient.Type.VEGGIES);
        Mockito.when(repo.save(Mockito.any(Ingredient.class)))
            .thenReturn(Mono.just(ingredient));

        testClient.post().uri("/api/v1/ingredients")
            .bodyValue(request("PICK", "Pickle", Ingredient.Type.VEGGIES))
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", ".*/api/v1/ingredients/PICK")
            .expectBody().jsonPath("$.id").isEqualTo("PICK");
    }

    @Test
    public void shouldExposeConfiguredIngredientClassificationMetadata() {
        IngredientRequest request = request("SLSA", "Hot salsa",
            Ingredient.Type.SAUCE);
        request.setDietaryTags(EnumSet.of(DietaryTag.VEGAN,
            DietaryTag.GLUTEN_FREE));
        request.setAllergens(EnumSet.of(Allergen.SESAME));
        request.setSpiceLevel(SpiceLevel.HOT);
        Mockito.when(repo.save(Mockito.any(Ingredient.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        testClient.post().uri("/api/ingredients")
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
                .jsonPath("$.dietaryTags[0]").isEqualTo("VEGAN")
                .jsonPath("$.dietaryTags[1]").isEqualTo("GLUTEN_FREE")
                .jsonPath("$.allergens[0]").isEqualTo("SESAME")
                .jsonPath("$.spiceLevel").isEqualTo("HOT");
    }

    @Test
    public void shouldReturnBadRequestForInvalidIngredient() {
        IngredientRequest invalidIngredient = request("", "", null);

        testClient.post().uri("/api/ingredients")
            .bodyValue(invalidIngredient)
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED");
        Mockito.verify(repo, Mockito.times(0)).save(Mockito.any(Ingredient.class));
    }

    private IngredientRequest request(
        String id, String name, Ingredient.Type type) {
        IngredientRequest request = new IngredientRequest();
        request.setId(id);
        request.setName(name);
        request.setType(type);
        return request;
    }

}
