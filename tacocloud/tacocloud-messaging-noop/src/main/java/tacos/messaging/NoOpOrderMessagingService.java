package tacos.messaging;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tacos.TacoOrder;

@Service
@Slf4j
public class NoOpOrderMessagingService
       implements OrderMessagingService {
  
  public void sendOrder(TacoOrder order) {
    int tacoCount = order.getTacos() == null ? 0 : order.getTacos().size();
    log.info("Sending order to kitchen: orderId={}, tacoCount={}",
        order.getId(), tacoCount);
  }
  
}
