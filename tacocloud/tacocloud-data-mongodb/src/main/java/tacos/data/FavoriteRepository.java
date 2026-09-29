package tacos.data;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Favorite;

@RepositoryRestResource(exported = false)
public interface FavoriteRepository extends ReactiveCrudRepository<Favorite, String> {
  Mono<Favorite> findByUserIdAndTacoId(String userId, String tacoId);

  Flux<Favorite> findByUserId(String userId, Pageable pageable);

  Mono<Long> countByUserId(String userId);
}
