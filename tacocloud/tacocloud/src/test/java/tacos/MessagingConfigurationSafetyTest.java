package tacos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

class MessagingConfigurationSafetyTest {
  @Test
  void shouldReadBrokerCredentialsOnlyFromEnvironment() throws Exception {
    try (InputStream input = new ClassPathResource("application.yml").getInputStream()) {
      String yaml = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
      assertThat(yaml).contains("password: ${TACOCLOUD_JMS_PASSWORD:}");
      assertThat(yaml).contains("password: ${TACOCLOUD_RABBIT_PASSWORD:}");
      assertThat(yaml).contains("transport: ${TACOCLOUD_MESSAGING_TRANSPORT:noop}");
    }
  }
}
