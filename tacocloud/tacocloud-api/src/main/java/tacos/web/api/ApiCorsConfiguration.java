package tacos.web.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiCorsConfiguration implements WebMvcConfigurer {

  private final String[] allowedOrigins;

  public ApiCorsConfiguration(
      @Value("${tacocloud.api.allowed-origins:http://localhost:8080}")
      String[] allowedOrigins) {
    this.allowedOrigins = allowedOrigins.clone();
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
        .allowedOrigins(allowedOrigins)
        .allowedMethods("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowCredentials(true);
  }
}
