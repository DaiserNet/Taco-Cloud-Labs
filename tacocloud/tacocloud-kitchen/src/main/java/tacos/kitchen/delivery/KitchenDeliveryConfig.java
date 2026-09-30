package tacos.kitchen.delivery;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("rabbitmq-listener")
public class KitchenDeliveryConfig {
  @Bean
  public MongoTransactionManager kitchenTransactionManager(
      MongoDatabaseFactory factory) {
    return new MongoTransactionManager(factory);
  }

  @Bean
  public TransactionTemplate kitchenTransactionTemplate(
      MongoTransactionManager manager) {
    return new TransactionTemplate(manager);
  }

  @Bean
  public Queue kitchenOrdersQueue(
      @Value("${tacocloud.messaging.rabbitmq.destination}") String name) {
    return QueueBuilder.durable(name).build();
  }

  @Bean
  public Queue kitchenRetryQueue(
      @Value("${tacocloud.messaging.rabbitmq.destination}") String name) {
    return QueueBuilder.durable(name + ".retry")
        .deadLetterExchange("")
        .deadLetterRoutingKey(name)
        .build();
  }

  @Bean
  public Queue kitchenDeadLetterQueue(
      @Value("${tacocloud.messaging.rabbitmq.destination}") String name) {
    return QueueBuilder.durable(name + ".dlq").build();
  }
}
