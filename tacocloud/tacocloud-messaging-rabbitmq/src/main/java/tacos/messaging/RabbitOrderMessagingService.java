package tacos.messaging;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


@Service
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "rabbitmq")
public class RabbitOrderMessagingService
       implements OrderMessagingService {
  
  private final RabbitTemplate rabbit;
  private final String destination;
  private final long confirmTimeoutMs;
  
  @Autowired
  public RabbitOrderMessagingService(RabbitTemplate rabbit,
      @Value("${tacocloud.messaging.rabbitmq.destination}") String destination,
      @Value("${tacocloud.messaging.rabbitmq.confirm-timeout-ms:30000}")
          long confirmTimeoutMs) {
    this.rabbit = rabbit;
    this.destination = destination;
    this.confirmTimeoutMs = confirmTimeoutMs;
  }
  
  public void sendOrder(OrderEvent event) {
    rabbit.convertAndSend(destination, event,
        new MessagePostProcessor() {
          @Override
          public Message postProcessMessage(Message message)
              throws AmqpException {
            MessageProperties props = message.getMessageProperties();
            props.setHeader("X_ORDER_SOURCE", "WEB");
            return message;
          } 
        });
  }

  @Override
  public Mono<Void> publish(OrderEvent event) {
    return Mono.fromRunnable(() -> rabbit.invoke(operations -> {
      operations.convertAndSend(destination, event, message -> {
        message.getMessageProperties().setHeader("X_ORDER_SOURCE", "WEB");
        return message;
      });
      operations.waitForConfirmsOrDie(confirmTimeoutMs);
      return null;
    })).subscribeOn(Schedulers.boundedElastic()).then();
  }
  
}
