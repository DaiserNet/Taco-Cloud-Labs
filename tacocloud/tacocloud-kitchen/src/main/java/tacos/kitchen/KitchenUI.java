package tacos.kitchen;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tacos.TacoOrder;

@Component
@Slf4j
public class KitchenUI {

  public void displayOrder(TacoOrder order) {
    int tacos = order == null || order.getTacos() == null
        ? 0 : order.getTacos().size();
    log.info("Kitchen notification received for {} tacos. Use the protected "
        + "Taco Cloud API queue at /api/kitchen/ui for claim and progress.", tacos);
  }
  
}
