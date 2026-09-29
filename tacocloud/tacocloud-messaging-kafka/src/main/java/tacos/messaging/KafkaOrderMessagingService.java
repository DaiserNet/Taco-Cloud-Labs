package tacos.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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

  @Override
  public Mono<Void> publish(OrderEvent event) {
    return Mono.<Void>create(sink -> kafkaTemplate.send(topic, event)
        .addCallback(result -> sink.success(), sink::error));
  }
  
}
