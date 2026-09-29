package tacos.web.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.api.dto.FavoritePageResponse;
import tacos.api.dto.FavoriteResponse;
import tacos.favorites.FavoriteService;

class FavoriteControllerTest {
  private final FavoriteService service = mock(FavoriteService.class);
  private final WebTestClient client = WebTestClient
      .bindToController(new FavoriteController(service)).build();

  @Test
  void shouldExposePaginatedFavoritesAtMeRoute() {
    when(service.list(eq(0), eq(20), isNull(Authentication.class)))
        .thenReturn(Mono.just(new FavoritePageResponse(Arrays.asList(
            new FavoriteResponse("A", "Taco A", false)), 0, 20, 1, 1)));

    client.get().uri("/api/users/me/favorites").exchange()
        .expectStatus().isOk().expectBody()
        .jsonPath("$.content[0].tacoId").isEqualTo("A")
        .jsonPath("$.content[0].orphaned").isEqualTo(false)
        .jsonPath("$.totalElements").isEqualTo(1);
  }

  @Test
  void shouldCompletePutAndDeleteWithNoContent() {
    when(service.add(eq("A"), isNull(Authentication.class)))
        .thenReturn(Mono.empty());
    when(service.remove(eq("A"), isNull(Authentication.class)))
        .thenReturn(Mono.empty());

    client.put().uri("/api/users/me/favorites/A").exchange()
        .expectStatus().isNoContent();
    client.delete().uri("/api/users/me/favorites/A").exchange()
        .expectStatus().isNoContent();
  }

  @Test
  void shouldReturnNotFoundForUnknownTaco() {
    when(service.add(eq("MISSING"), isNull(Authentication.class)))
        .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));

    client.put().uri("/api/users/me/favorites/MISSING").exchange()
        .expectStatus().isNotFound();
  }
}
