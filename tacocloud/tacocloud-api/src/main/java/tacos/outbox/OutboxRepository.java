package tacos.outbox;

import java.util.Collection;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface OutboxRepository extends ReactiveCrudRepository<OutboxEvent, String> {
  Mono<Long> countByStatusIn(Collection<OutboxEvent.Status> statuses);
}
