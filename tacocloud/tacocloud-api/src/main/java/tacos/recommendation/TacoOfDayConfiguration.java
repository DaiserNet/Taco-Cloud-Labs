package tacos.recommendation;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TacoOfDayConfiguration {
  @Bean
  public ZoneId tacoOfDayZone(
      @Value("${tacocloud.taco-of-day.zone:UTC}") String zone) {
    return ZoneId.of(zone);
  }
}
