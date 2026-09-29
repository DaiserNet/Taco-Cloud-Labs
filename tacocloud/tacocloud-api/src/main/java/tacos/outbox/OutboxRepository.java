package tacos.outbox;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface OutboxRepository extends ReactiveCrudRepository<OutboxEvent, String> { }
