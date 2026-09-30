package tacos.kitchen.delivery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import tacos.kitchen.KitchenUI;
import tacos.kitchen.messaging.rabbit.listener.OrderListener;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;

class RabbitOrderListenerTest {
  @Test
  void shouldNotAckWhenDeadLetterPublishIsUnconfirmed() throws Exception {
    ObjectMapper json = new ObjectMapper();
    OrderEvent event = OrderEvent.created("correlation-1",
        new OrderEventPayload("ORDER-1", Collections.emptyList()));
    KitchenEventProcessor processor = mock(KitchenEventProcessor.class);
    when(processor.process(any(OrderEvent.class)))
        .thenThrow(new PermanentOrderEventException("UNSUPPORTED_VERSION"));
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    Channel channel = mock(Channel.class);
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(7L);
    Message message = new Message(json.writeValueAsBytes(event), properties);
    OrderListener listener = new OrderListener(processor, mock(KitchenUI.class),
        rabbit, json, new SimpleMeterRegistry(), "orders", 0, 100, 100, 1);

    listener.receiveOrder(message, channel);

    verify(channel).basicNack(7L, false, true);
    verify(channel, never()).basicAck(7L, false);
  }

  @Test
  void shouldTreatUnknownMongoCommitResultAsRetryable() throws Exception {
    ObjectMapper json = new ObjectMapper();
    OrderEvent event = OrderEvent.created("correlation-2",
        new OrderEventPayload("ORDER-2", Collections.emptyList()));
    MongoException uncertain = new MongoException("commit result unknown");
    uncertain.addLabel("UnknownTransactionCommitResult");
    KitchenEventProcessor processor = mock(KitchenEventProcessor.class);
    when(processor.process(any(OrderEvent.class))).thenThrow(uncertain);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    Channel channel = mock(Channel.class);
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(8L);
    OrderListener listener = new OrderListener(processor, mock(KitchenUI.class),
        rabbit, json, new SimpleMeterRegistry(), "orders", 1, 100, 100, 1);

    listener.receiveOrder(new Message(json.writeValueAsBytes(event), properties),
        channel);

    verify(rabbit).send(eq(""), eq("orders.retry"), any(Message.class),
        any(CorrelationData.class));
    verify(channel).basicNack(8L, false, true);
    verify(channel, never()).basicAck(8L, false);
  }
}
