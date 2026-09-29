package tacos.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "kafka")
public class KafkaOrderMessagingService
                                  implements OrderMessagingService {
  
  private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
  private final String topic;
  
  @Autowired
  public KafkaOrderMessagingService(
          KafkaTemplate<String, OrderEvent> kafkaTemplate,
          @Value("${tacocloud.messaging.kafka.topic}") String topic) {
    this.kafkaTemplate = kafkaTemplate;
    this.topic = topic;
  }
  
  @Override
  public void sendOrder(OrderEvent event) {
    kafkaTemplate.send(topic, event);
  }
  
}
