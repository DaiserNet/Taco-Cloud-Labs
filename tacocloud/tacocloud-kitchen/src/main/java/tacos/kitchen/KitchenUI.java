package tacos.kitchen;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tacos.messaging.OrderEvent;

@Component
@Slf4j
public class KitchenUI {

  public void displayOrder(OrderEvent order) {
    int tacos = order == null ? 0 : order.getPayload().getItems().stream()
        .mapToInt(item -> item.getQuantity()).sum();
    log.info("Kitchen notification received for {} tacos. Use the protected "
        + "Taco Cloud API queue at /api/kitchen/ui for claim and progress.", tacos);
  }
  
}
