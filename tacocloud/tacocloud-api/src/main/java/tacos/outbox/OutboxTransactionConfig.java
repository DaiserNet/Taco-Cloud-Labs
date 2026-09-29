package tacos.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class OutboxTransactionConfig {
  @Bean
  ReactiveMongoTransactionManager reactiveMongoTransactionManager(
      ReactiveMongoDatabaseFactory factory) {
    return new ReactiveMongoTransactionManager(factory);
  }

  @Bean
  TransactionalOperator outboxTransactionalOperator(
      ReactiveMongoTransactionManager manager) {
    return TransactionalOperator.create(manager);
  }
}
