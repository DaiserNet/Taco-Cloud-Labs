package tacos.web.api;

import java.net.URI;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.data.IngredientRepository;

@RestController
@RequestMapping(path="/api/ingredients", produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class IngredientController {

  private IngredientRepository repo;

  @Autowired
  public IngredientController(IngredientRepository repo) {
    this.repo = repo;
  }

  @GetMapping
  public Flux<Ingredient> allIngredients() {
    return repo.findAll();
  }

  @GetMapping("/{id}")
  public Mono<Ingredient> byId(@PathVariable String id) {
    return repo.findById(id);
  }

  @PutMapping("/{id}")
  public Mono<ResponseEntity<Ingredient>> updateIngredient(@PathVariable String id, @RequestBody Ingredient ingredient) {
    if (!ingredient.getId().equals(id)) {
      // throw new IllegalStateException("Given ingredient's ID doesn't match the ID in the path.");
      return Mono.just(ResponseEntity.badRequest().build());
    }

    return repo.findById(id).flatMap(existingIngredient -> {
      existingIngredient.setName(ingredient.getName());
      existingIngredient.setType(ingredient.getType());
      return repo.save(existingIngredient);
    })
        .map(updatedIngredient -> ResponseEntity.ok(updatedIngredient))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PostMapping
  public Mono<ResponseEntity<Ingredient>> postIngredient(@Valid @RequestBody Ingredient ingredient, UriComponentsBuilder uriBuilder) {

    return Mono.just(ingredient)
        .flatMap(repo::save)
        .map(i -> {
          URI location = uriBuilder
              .path("/api/ingredients/{id}")
              .buildAndExpand(i.getId())
              .toUri();
          return ResponseEntity.created(location).body(i);
        });
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Ingredient>> deleteIngredient(@PathVariable String id) {
    // repo.deleteById(id);
    return repo.findById(id).flatMap(existingIngredient -> repo.delete(existingIngredient)
        .thenReturn(ResponseEntity.noContent().<Ingredient>build()))
    .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
  }

}
