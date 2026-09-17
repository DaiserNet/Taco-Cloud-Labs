package tacos.web.api;

import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.data.IngredientRepository;

import org.junit.jupiter.api.Test;

    @ContextConfiguration(classes = IngredientController.class)
    @WebFluxTest(controllers = IngredientController.class, excludeAutoConfiguration = { org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration.class })
public class IngredientControllerTest {
    
    @Autowired
    private WebTestClient testClient;

    @MockBean
    private IngredientRepository repo; 

    @Autowired
    private IngredientController controller;

    // Put test case
    @Test
    public void shouldUpdateIngredient() {
        Mockito.when(repo.findById("FLTO"))
            .thenReturn(Mono.just(new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP)));
        Mockito.when(repo.save(Mockito.any(Ingredient.class)))
            .thenReturn(Mono.just(new Ingredient("FLTO", "New Flour Tortilla", Ingredient.Type.WRAP)));    
        testClient.put().uri("/api/ingredients/FLTO")
            .bodyValue(new Ingredient("FLTO", "New Flour Tortilla", Ingredient.Type.WRAP))
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
            .bodyValue(new Ingredient("LFTO", "Lettuce", Ingredient.Type.VEGGIES))
            .exchange()
            .expectStatus().isBadRequest();
        Mockito.verify(repo, Mockito.times(0)).save(Mockito.any(Ingredient.class));
    }

    @Test
    public void shouldReturnNotFoundForNonexistentIngredient() {
        Mockito.when(repo.findById("LFTO")).thenReturn(Mono.empty());
        testClient.put().uri("/api/ingredients/LFTO")
            .bodyValue(new Ingredient("LFTO", "Lettuce", Ingredient.Type.VEGGIES))
            .exchange()
            .expectStatus().isNotFound();
        Mockito.verify(repo, Mockito.times(0)).save(Mockito.any(Ingredient.class));
    }

    @Test
    public void shouldEmitAndComplete() {
        Ingredient ingredient = new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP);
        
        PublisherProbe<Ingredient> saveProbe = PublisherProbe.of(Mono.just(ingredient));
        
        Mockito.when(repo.findById("FLTO")).thenReturn(Mono.just(ingredient));
        Mockito.when(repo.save(Mockito.any(Ingredient.class))).thenReturn(saveProbe.mono());

        Mono<ResponseEntity<Ingredient>> responseMono = controller.updateIngredient("FLTO", ingredient);


        StepVerifier.create(responseMono)
            .expectNextMatches(response -> response.getStatusCode().is2xxSuccessful() && response.getBody().equals(ingredient))
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
            .expectStatus().isNotFound();
        Mockito.verify(repo, Mockito.times(0)).delete(Mockito.any(Ingredient.class));
    }

    // Post test case
    @Test
    public void shouldCreateIngredient() {
        Ingredient ingredient = new Ingredient("PICK", "Pickle", Ingredient.Type.VEGGIES);

        Mockito.when(repo.save(Mockito.any(Ingredient.class)))
            .thenReturn(Mono.just(ingredient));

        testClient.post().uri("/api/ingredients")
            .bodyValue(ingredient)
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
    public void shouldReturnBadRequestForInvalidIngredient() {
        Ingredient invalidIngredient = new Ingredient("", "", null);

        testClient.post().uri("/api/ingredients")
            .bodyValue(invalidIngredient)
            .exchange()
            .expectStatus().isBadRequest();
        Mockito.verify(repo, Mockito.times(0)).save(Mockito.any(Ingredient.class));
    }

}
