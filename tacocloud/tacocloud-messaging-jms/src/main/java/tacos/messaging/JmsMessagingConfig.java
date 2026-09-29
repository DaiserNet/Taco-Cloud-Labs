package tacos.messaging;

import java.util.Collections;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;

@Configuration
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "jms")
public class JmsMessagingConfig {
  @Bean
  public MappingJackson2MessageConverter jmsOrderEventConverter() {
    MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
    converter.setTypeIdPropertyName("_typeId");
    converter.setTypeIdMappings(Collections.singletonMap("order", OrderEvent.class));
    return converter;
  }
}
