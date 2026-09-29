package tacos.messaging;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class NoOpOrderMessagingService
       implements OrderMessagingService {
  
  public void sendOrder(OrderEvent event) {
    int tacoCount = event.getPayload().getItems().stream()
        .mapToInt(OrderEventPayload.Item::getQuantity).sum();
    log.info("Sending order to kitchen: orderId={}, tacoCount={}",
        event.getPayload().getOrderId(), tacoCount);
  }
  
}
