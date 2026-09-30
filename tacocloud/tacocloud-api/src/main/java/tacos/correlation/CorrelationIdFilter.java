package tacos.correlation;

import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Correlation-Id";
  private static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
  private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = (String) request.getAttribute(ATTRIBUTE);
    if (id == null) {
      Enumeration<String> headers = request.getHeaders(HEADER);
      String candidate = headers.hasMoreElements() ? headers.nextElement() : null;
      id = candidate != null && !headers.hasMoreElements()
          && candidate.matches("[A-Za-z0-9._:-]{1,100}")
          ? candidate : UUID.randomUUID().toString();
      request.setAttribute(ATTRIBUTE, id);
    }
    response.setHeader(HEADER, id);
    String previous = MDC.get(CorrelationContext.MDC_KEY);
    MDC.put(CorrelationContext.MDC_KEY, id);
    try {
      if (!isAsyncDispatch(request)) {
        log.info("HTTP request received");
      }
      chain.doFilter(request, response);
    } finally {
      if (previous == null) {
        MDC.remove(CorrelationContext.MDC_KEY);
      } else {
        MDC.put(CorrelationContext.MDC_KEY, previous);
      }
    }
  }
}
