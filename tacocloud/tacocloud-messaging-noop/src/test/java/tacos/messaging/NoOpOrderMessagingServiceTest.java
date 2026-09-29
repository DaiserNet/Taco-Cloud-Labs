package tacos.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Collections;

class NoOpOrderMessagingServiceTest {

  @Test
  void shouldLogOperationalMetadataWithoutPaymentInformation() {
    Logger logger =
        (Logger) LoggerFactory.getLogger(NoOpOrderMessagingService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      OrderEventPayload.Item item = new OrderEventPayload.Item("Test taco", 3,
          Collections.emptyList());
      OrderEvent event = OrderEvent.created("ORDER-ID", new OrderEventPayload(
          "ORDER-ID", Collections.singletonList(item)));
      new NoOpOrderMessagingService().sendOrder(event);

      String message = appender.list.get(0).getFormattedMessage();
      assertTrue(message.contains("ORDER-ID"));
      assertTrue(message.contains("tacoCount=3"));
      assertFalse(message.contains("PAYMENT-ID"));
      assertFalse(message.contains("VISA"));
      assertFalse(message.contains("0002"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
