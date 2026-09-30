package tacos.correlation;

import java.util.UUID;

import org.slf4j.MDC;

import reactor.core.publisher.Mono;

/** Transfers the request ID to Reactor Context before MVC subscribes. */
public final class CorrelationContext {
  public static final String MDC_KEY = "correlationId";
  public static final String CONTEXT_KEY = CorrelationContext.class.getName();

  private CorrelationContext() { }

  public static <T> Mono<T> fromCurrentRequest(Mono<T> publisher) {
    String id = MDC.get(MDC_KEY);
    if (id == null) {
      id = UUID.randomUUID().toString();
    }
    String correlationId = id;
    return publisher.contextWrite(context -> context.put(CONTEXT_KEY, correlationId));
  }
}
