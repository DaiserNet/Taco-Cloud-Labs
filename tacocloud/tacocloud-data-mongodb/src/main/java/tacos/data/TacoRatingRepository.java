package tacos.data;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import reactor.core.publisher.Mono;
import tacos.TacoRating;

@RepositoryRestResource(exported = false)
public interface TacoRatingRepository
    extends ReactiveCrudRepository<TacoRating, String> {
  Mono<TacoRating> findByUserIdAndTacoId(String userId, String tacoId);

  Mono<Long> countByTacoId(String tacoId);
}
