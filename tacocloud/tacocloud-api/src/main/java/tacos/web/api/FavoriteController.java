package tacos.web.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.FavoritePageResponse;
import tacos.favorites.FavoriteService;

@RestController
@RequestMapping(path = "/api/users/me/favorites", produces = "application/json")
public class FavoriteController {
  private final FavoriteService favoriteService;

  public FavoriteController(FavoriteService favoriteService) {
    this.favoriteService = favoriteService;
  }

  @PutMapping("/{tacoId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> add(@PathVariable String tacoId,
      Authentication authentication) {
    return favoriteService.add(tacoId, authentication);
  }

  @DeleteMapping("/{tacoId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> remove(@PathVariable String tacoId,
      Authentication authentication) {
    return favoriteService.remove(tacoId, authentication);
  }

  @GetMapping
  public Mono<FavoritePageResponse> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication authentication) {
    return favoriteService.list(page, size, authentication);
  }
}
