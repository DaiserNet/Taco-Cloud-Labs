package tacos.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.inventory.InsufficientStockException;

@Component
public class OrderMetrics {
  private final MeterRegistry registry;
  private final String transport;

  public OrderMetrics(MeterRegistry registry,
      @Value("${tacocloud.messaging.transport:noop}") String transport) {
    this.registry = registry;
    this.transport = safeTransport(transport);
  }

  public Mono<TacoOrder> placement(Mono<TacoOrder> operation, String origin) {
    String source = safeSource(origin);
    return Mono.defer(() -> {
      Timer.Sample sample = Timer.start(registry);
      return operation
          .doOnSuccess(order -> {
            if (order == null) {
              failed(source);
              sample.stop(timer(source, "empty"));
              return;
            }
            registry.counter("tacocloud.orders.created", "source", source,
                "transport", transport).increment();
            if (order.isCouponApplied()) {
              registry.counter("tacocloud.coupons.applied", "source", source)
                  .increment();
            }
            sample.stop(timer(source, "created"));
          })
          .doOnError(error -> {
            failed(source);
            if (error instanceof InsufficientStockException) {
              registry.counter("tacocloud.inventory.stock.rejected",
                  "source", source).increment();
            }
            sample.stop(timer(source, "failed"));
          });
    });
  }

  public void cancelled() {
    registry.counter("tacocloud.orders.cancelled", "source", "customer")
        .increment();
  }

  private void failed(String source) {
    registry.counter("tacocloud.orders.failed", "source", source,
        "transport", transport).increment();
  }

  private Timer timer(String source, String result) {
    return registry.timer("tacocloud.orders.placement", "source", source,
        "result", result, "transport", transport);
  }

  private String safeSource(String origin) {
    if ("EMAIL".equals(origin) || "REORDER".equals(origin)) {
      return origin;
    }
    return "HTTP_API";
  }

  private String safeTransport(String value) {
    if ("jms".equals(value) || "rabbitmq".equals(value)
        || "kafka".equals(value)) {
      return value;
    }
    return "noop";
  }
}
