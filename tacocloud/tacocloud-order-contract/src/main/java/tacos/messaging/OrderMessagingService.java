package tacos.messaging;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public interface OrderMessagingService {
  void sendOrder(OrderEvent event);

  default Mono<Void> publish(OrderEvent event) {
    return Mono.fromRunnable(() -> sendOrder(event))
        .subscribeOn(Schedulers.boundedElastic()).then();
  }
}
