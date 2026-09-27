package tacos.messaging;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tacos.TacoOrder;

@Service
@Slf4j
public class NoOpOrderMessagingService
       implements OrderMessagingService {
  
  public void sendOrder(TacoOrder order) {
    int tacoCount = order.getItems() == null ? 0 : order.getItems().stream()
        .mapToInt(item -> item == null ? 0 : item.getQuantity())
        .sum();
    if (tacoCount == 0 && order.getTacos() != null) {
      tacoCount = order.getTacos().size();
    }
    log.info("Sending order to kitchen: orderId={}, tacoCount={}",
        order.getId(), tacoCount);
  }
  
}
