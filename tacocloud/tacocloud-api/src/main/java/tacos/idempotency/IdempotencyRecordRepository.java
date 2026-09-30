package tacos.idempotency;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import reactor.core.publisher.Mono;

@RepositoryRestResource(exported = false)
public interface IdempotencyRecordRepository
    extends ReactiveMongoRepository<IdempotencyRecord, String> {
  Mono<IdempotencyRecord> findByUserIdAndKey(String userId, String key);
}
