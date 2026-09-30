package tacos.kitchen.messaging.rabbit.listener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import tacos.kitchen.KitchenUI;
import tacos.kitchen.delivery.KitchenEventProcessor;
import tacos.kitchen.delivery.PermanentOrderEventException;
import tacos.messaging.OrderEvent;

@Profile("rabbitmq-listener")
@Component
public class OrderListener {
  private static final String RETRIES = "x-tacocloud-retry-count";
  private static final String CAUSE = "x-tacocloud-cause";
  private static final String EVENT_ID = "x-tacocloud-event-id";

  private final KitchenEventProcessor processor;
  private final KitchenUI ui;
  private final RabbitTemplate rabbit;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final String destination;
  private final int maxRetries;
  private final long backoffMs;
  private final long maxBackoffMs;
  private final long confirmTimeoutMs;

  public OrderListener(KitchenEventProcessor processor, KitchenUI ui,
      RabbitTemplate rabbit, ObjectMapper json, MeterRegistry metrics,
      @Value("${tacocloud.messaging.rabbitmq.destination}") String destination,
      @Value("${tacocloud.kitchen.consumer.max-retries:3}") int maxRetries,
      @Value("${tacocloud.kitchen.consumer.backoff-ms:1000}") long backoffMs,
      @Value("${tacocloud.kitchen.consumer.max-backoff-ms:30000}") long maxBackoffMs,
      @Value("${tacocloud.kitchen.consumer.confirm-timeout-ms:30000}")
          long confirmTimeoutMs) {
    if (maxRetries < 0 || backoffMs < 1 || maxBackoffMs < backoffMs
        || confirmTimeoutMs < 1) {
      throw new IllegalArgumentException("Invalid kitchen consumer settings");
    }
    this.processor = processor;
    this.ui = ui;
    this.rabbit = rabbit;
    this.json = json;
    this.metrics = metrics;
    this.destination = destination;
    this.maxRetries = maxRetries;
    this.backoffMs = backoffMs;
    this.maxBackoffMs = maxBackoffMs;
    this.confirmTimeoutMs = confirmTimeoutMs;
  }

  @RabbitListener(queues = "${tacocloud.messaging.rabbitmq.destination}")
  public void receiveOrder(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    OrderEvent event = null;
    KitchenEventProcessor.Result result;
    try {
      event = json.readValue(message.getBody(), OrderEvent.class);
      result = processor.process(event);
    } catch (Exception failure) {
      handleFailure(message, event, failure, channel, deliveryTag);
      return;
    }
    channel.basicAck(deliveryTag, false);
    if (result == KitchenEventProcessor.Result.DUPLICATE) {
      count("duplicate");
    } else {
      count("processed");
      ui.displayOrder(event);
    }
  }

  private void handleFailure(Message source, OrderEvent event,
      Exception failure, Channel channel, long deliveryTag) throws IOException {
    boolean transientFailure = transientFailure(failure);
    int retries = retryCount(source);
    boolean retry = transientFailure && retries < maxRetries;
    String target = destination + (retry ? ".retry" : ".dlq");
    int nextRetry = retry ? retries + 1 : retries;
    String cause = failure instanceof PermanentOrderEventException
        ? ((PermanentOrderEventException) failure).getCode()
        : event == null ? "INVALID_EVENT"
        : transientFailure ? "TRANSIENT_EXHAUSTED" : "PERMANENT_FAILURE";
    try {
      Message routed = routedMessage(event, nextRetry, cause,
          retry ? delay(nextRetry) : 0);
      CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
      rabbit.send("", target, routed, correlation);
      CorrelationData.Confirm confirm = correlation.getFuture()
          .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
      if (!confirm.isAck() || correlation.getReturned() != null) {
        throw new IllegalStateException("Kitchen message was not routed");
      }
      channel.basicAck(deliveryTag, false);
      count(retry ? "retry" : "dlq");
    } catch (Exception routeFailure) {
      channel.basicNack(deliveryTag, false, true);
      count("route_error");
    }
  }

  private Message routedMessage(OrderEvent event, int retries,
      String cause, long delay) throws IOException {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setHeader(RETRIES, retries);
    properties.setHeader(CAUSE, cause);
    if (event != null) {
      properties.setHeader(EVENT_ID, event.getEventId());
      String correlation = event.getCorrelationId();
      if (correlation.matches("[A-Za-z0-9._:-]{1,100}")) {
        properties.setCorrelationId(correlation);
      }
    }
    if (delay > 0) {
      properties.setExpiration(Long.toString(delay));
    }
    return new Message(event == null ? "{}".getBytes(StandardCharsets.UTF_8)
        : json.writeValueAsBytes(event), properties);
  }

  private int retryCount(Message message) {
    Object value = message.getMessageProperties().getHeaders().get(RETRIES);
    return value instanceof Number ? Math.max(0, ((Number) value).intValue()) : 0;
  }

  private long delay(int retry) {
    long value = backoffMs;
    for (int i = 1; i < retry && value < maxBackoffMs; i++) {
      value = Math.min(maxBackoffMs,
          value > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : value * 2);
    }
    return value;
  }

  private boolean transientFailure(Throwable failure) {
    for (Throwable current = failure; current != null;
        current = current.getCause()) {
      if (current instanceof TransientDataAccessException
          || current instanceof DataAccessResourceFailureException) {
        return true;
      }
      if (current instanceof MongoException
          && (((MongoException) current).hasErrorLabel("TransientTransactionError")
              || ((MongoException) current).hasErrorLabel("UnknownTransactionCommitResult")
              || ((MongoException) current).hasErrorLabel("RetryableWriteError"))) {
        return true;
      }
    }
    return false;
  }

  private void count(String outcome) {
    metrics.counter("tacocloud.kitchen.event.delivery", "outcome", outcome)
        .increment();
  }
}
