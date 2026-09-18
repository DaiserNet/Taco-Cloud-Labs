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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.api.dto.IngredientRequest;
import tacos.api.dto.IngredientResponse;
import tacos.api.mapper.IngredientMapper;
import tacos.data.IngredientRepository;

@RestController
@RequestMapping(path="/api/ingredients", produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class IngredientController {

  private IngredientRepository repo;
  private IngredientMapper ingredientMapper;

  @Autowired
  public IngredientController(IngredientRepository repo, IngredientMapper ingredientMapper) {
    this.repo = repo;
    this.ingredientMapper = ingredientMapper;
  }

  @GetMapping
  public Flux<IngredientResponse> allIngredients() {
    return repo.findAll().map(ingredientMapper::toResponse);
  }

  @GetMapping("/{id}")
  public Mono<IngredientResponse> byId(@PathVariable String id) {
    return repo.findById(id)
        .map(ingredientMapper::toResponse)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
  }

  @PutMapping("/{id}")
  public Mono<ResponseEntity<IngredientResponse>> updateIngredient(
      @PathVariable String id, @Valid @RequestBody IngredientRequest request) {
    if (!id.equals(request.getId())) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
    }

    return repo.findById(id).flatMap(existingIngredient -> {
      ingredientMapper.updateEntity(request, existingIngredient);
      return repo.save(existingIngredient);
    })
        .map(ingredientMapper::toResponse)
        .map(ResponseEntity::ok)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
  }

  @PostMapping
  public Mono<ResponseEntity<IngredientResponse>> postIngredient(
      @Valid @RequestBody IngredientRequest request, UriComponentsBuilder uriBuilder) {
    return repo.save(ingredientMapper.toEntity(request))
        .map(ingredient -> {
          URI location = uriBuilder
              .path("/api/ingredients/{id}")
              .buildAndExpand(ingredient.getId())
              .toUri();
          return ResponseEntity.created(location)
              .body(ingredientMapper.toResponse(ingredient));
        });
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> deleteIngredient(@PathVariable String id) {
    return repo.findById(id).flatMap(existingIngredient -> repo.delete(existingIngredient)
        .thenReturn(ResponseEntity.noContent().<Void>build()))
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
  }

}
