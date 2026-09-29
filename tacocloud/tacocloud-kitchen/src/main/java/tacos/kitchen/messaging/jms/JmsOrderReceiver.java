package tacos.kitchen.messaging.jms;

import org.springframework.context.annotation.Profile;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import tacos.messaging.OrderEvent;
import tacos.kitchen.OrderReceiver;

@Profile("jms-template")
@Component("templateOrderReceiver")
public class JmsOrderReceiver implements OrderReceiver {

  private JmsTemplate jms;

  public JmsOrderReceiver(JmsTemplate jms) {
    this.jms = jms;
  }
  
  @Override
  public OrderEvent receiveOrder() {
    return (OrderEvent) jms.receiveAndConvert("tacocloud.order.queue");
  }
  
}
