package tacos.pricing;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CouponClockConfiguration {

  @Bean
  public Clock couponClock() {
    return Clock.systemUTC();
  }
}
