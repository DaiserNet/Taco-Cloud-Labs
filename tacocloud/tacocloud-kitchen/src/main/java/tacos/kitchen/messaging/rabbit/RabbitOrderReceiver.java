package tacos.kitchen.messaging.rabbit;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tacos.messaging.OrderEvent;
import tacos.kitchen.OrderReceiver;

@Profile("rabbitmq-template")
@Component("templateOrderReceiver")
public class RabbitOrderReceiver implements OrderReceiver {

  private RabbitTemplate rabbit;
  private final String destination;

  public RabbitOrderReceiver(RabbitTemplate rabbit,
      @Value("${tacocloud.messaging.rabbitmq.destination}") String destination) {
    this.rabbit = rabbit;
    this.destination = destination;
  }
  
  public OrderEvent receiveOrder() {
    return (OrderEvent) rabbit.receiveAndConvert(destination);
  }
  
}
