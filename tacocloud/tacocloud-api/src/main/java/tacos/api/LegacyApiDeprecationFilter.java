package tacos.api;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyApiDeprecationFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(
        request.getContextPath().length());
    if (path.startsWith("/api/") && !path.startsWith("/api/v1/")) {
      String query = request.getQueryString() == null ? ""
          : "?" + request.getQueryString();
      response.setHeader("Deprecation", "true");
      response.setHeader("Link", "<" + request.getContextPath()
          + "/api/v1" + path.substring(4) + query
          + ">; rel=\"successor-version\"");
    }
    chain.doFilter(request, response);
  }
}
