package tacos.messaging;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Checks transport selection before application services are instantiated. */
@Configuration
public class MessagingTransportGuard {
  private static final List<String> SUPPORTED =
      Arrays.asList("noop", "jms", "rabbitmq", "kafka");

  @Bean
  public static BeanFactoryPostProcessor validateMessagingTransport(Environment environment) {
    return factory -> {
      String value = environment.getProperty("tacocloud.messaging.transport", "noop");
      if (!SUPPORTED.contains(value)) {
        throw new IllegalStateException("Unknown tacocloud.messaging.transport='"
            + value + "'. Expected one of " + SUPPORTED);
      }
      boolean production = environment.acceptsProfiles(Profiles.of("prod", "production"));
      if (production && "noop".equals(value)) {
        throw new IllegalStateException("Production requires an explicit broker transport; "
            + "tacocloud.messaging.transport=noop is not allowed");
      }
      if ("noop".equals(value) && Arrays.stream(environment.getActiveProfiles())
          .anyMatch(profile -> !"dev".equals(profile) && !"test".equals(profile))) {
        throw new IllegalStateException("Noop messaging is only allowed for dev/test; "
            + "configure tacocloud.messaging.transport for this profile");
      }
      if (production && "jms".equals(value)) {
        requireCredential(environment, "spring.artemis.user");
        requireCredential(environment, "spring.artemis.password");
      }
      if (production && "rabbitmq".equals(value)) {
        requireCredential(environment, "spring.rabbitmq.username");
        requireCredential(environment, "spring.rabbitmq.password");
      }
      String[] beans = factory.getBeanNamesForType(OrderMessagingService.class,
          false, false);
      if (beans.length != 1) {
        throw new IllegalStateException("Expected exactly one OrderMessagingService for "
            + "tacocloud.messaging.transport=" + value + ", found "
            + beans.length + ": " + Arrays.toString(beans));
      }
    };
  }

  private static void requireCredential(Environment environment, String key) {
    String value = environment.getProperty(key);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalStateException("Production messaging requires " + key);
    }
  }
}
