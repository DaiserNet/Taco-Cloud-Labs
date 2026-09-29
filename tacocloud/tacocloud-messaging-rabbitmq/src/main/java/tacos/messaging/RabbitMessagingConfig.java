package tacos.messaging;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "rabbitmq")
public class RabbitMessagingConfig {
  @Bean
  public Jackson2JsonMessageConverter rabbitOrderEventConverter() {
    return new Jackson2JsonMessageConverter();
  }
}
