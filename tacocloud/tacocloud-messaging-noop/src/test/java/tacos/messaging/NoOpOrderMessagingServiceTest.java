package tacos.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tacos.OrderLine;
import tacos.Taco;
import tacos.TacoOrder;

class NoOpOrderMessagingServiceTest {

  @Test
  void shouldLogOperationalMetadataWithoutPaymentInformation() {
    Logger logger =
        (Logger) LoggerFactory.getLogger(NoOpOrderMessagingService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      TacoOrder order = new TacoOrder();
      order.setId("ORDER-ID");
      order.setPaymentMethodId("PAYMENT-ID");
      order.setPaymentBrand("VISA");
      order.setPaymentLast4("0002");
      OrderLine line = new OrderLine();
      line.setTaco(new Taco());
      line.setQuantity(3);
      order.addItem(line);

      new NoOpOrderMessagingService().sendOrder(order);

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
