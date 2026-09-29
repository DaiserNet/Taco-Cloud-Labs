package tacos.favorites;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.Favorite;
import tacos.User;
import tacos.api.dto.FavoritePageResponse;
import tacos.api.dto.FavoriteResponse;
import tacos.data.FavoriteRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@Service
public class FavoriteService {
  private final FavoriteRepository favoriteRepo;
  private final TacoRepository tacoRepo;
  private final UserRepository userRepo;
  private final ReactiveMongoTemplate mongo;
  private final int maxPageSize;

  public FavoriteService(FavoriteRepository favoriteRepo, TacoRepository tacoRepo,
      UserRepository userRepo, ReactiveMongoTemplate mongo,
      @Value("${tacocloud.favorites.max-page-size:50}") int maxPageSize) {
    this.favoriteRepo = favoriteRepo;
    this.tacoRepo = tacoRepo;
    this.userRepo = userRepo;
    this.mongo = mongo;
    this.maxPageSize = maxPageSize;
  }

  public Mono<Void> add(String tacoId, Authentication authentication) {
    return currentUser(authentication).flatMap(user ->
        tacoRepo.existsById(tacoId).flatMap(exists -> {
          if (!exists) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "El taco no existe."));
          }
          return favoriteRepo.save(new Favorite(user.getId(), tacoId))
              .onErrorResume(DuplicateKeyException.class,
                  error -> Mono.empty())
              .then();
        }));
  }

  public Mono<Void> remove(String tacoId, Authentication authentication) {
    return currentUser(authentication)
        .flatMap(user -> favoriteRepo
            .findByUserIdAndTacoId(user.getId(), tacoId)
            .flatMap(favoriteRepo::delete).then());
  }

  public Mono<FavoritePageResponse> list(int page, int size,
      Authentication authentication) {
    return Mono.defer(() -> {
      if (page < 0 || size < 1 || size > maxPageSize) {
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "page o size fuera de rango."));
      }
      PageRequest paging = PageRequest.of(page, size,
          Sort.by(Sort.Order.asc("tacoId"), Sort.Order.asc("id")));
      return currentUser(authentication).flatMap(user ->
          Mono.zip(favoriteRepo.findByUserId(user.getId(), paging)
                  .concatMap(this::toResponse).collectList(),
              favoriteRepo.countByUserId(user.getId()))
              .map(result -> {
                long total = result.getT2();
                int totalPages = (int) (total / size
                    + (total % size == 0 ? 0 : 1));
                return new FavoritePageResponse(result.getT1(), page, size,
                    total, totalPages);
              }));
    });
  }

  private Mono<FavoriteResponse> toResponse(Favorite favorite) {
    return tacoRepo.findById(favorite.getTacoId())
        .flatMap(taco -> markOrphaned(favorite, false)
            .thenReturn(new FavoriteResponse(favorite.getTacoId(),
                taco.getName(), false)))
        .switchIfEmpty(Mono.defer(() -> markOrphaned(favorite, true)
            .thenReturn(new FavoriteResponse(favorite.getTacoId(), null,
                true))));
  }

  private Mono<Void> markOrphaned(Favorite favorite, boolean orphaned) {
    if (favorite.isOrphaned() == orphaned) {
      return Mono.empty();
    }
    Query query = Query.query(Criteria.where("_id").is(favorite.getId())
        .and("userId").is(favorite.getUserId()));
    return mongo.updateFirst(query, Update.update("orphaned", orphaned),
        Favorite.class).then();
  }

  private Mono<User> currentUser(Authentication authentication) {
    return Mono.defer(() -> {
      if (authentication == null || !authentication.isAuthenticated()
          || authentication instanceof AnonymousAuthenticationToken) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      boolean userRole = authentication.getAuthorities().stream()
          .anyMatch(authority -> "ROLE_USER".equals(authority.getAuthority()));
      if (!userRole) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
      }
      return userRepo.findByUsername(authentication.getName())
          .filter(user -> StringUtils.hasText(user.getId()))
          .switchIfEmpty(Mono.error(
              new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    });
  }
}
