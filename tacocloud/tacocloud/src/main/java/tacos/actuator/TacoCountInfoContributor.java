package tacos.actuator;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tacos.data.TacoRepository;

@Component
public class TacoCountInfoContributor implements InfoContributor {
  private static final Logger log = LoggerFactory.getLogger(
      TacoCountInfoContributor.class);
  private final TacoRepository tacoRepo;
  private final AtomicLong tacoCount = new AtomicLong(-1);

  public TacoCountInfoContributor(TacoRepository tacoRepo) {
    this.tacoRepo = tacoRepo;
  }

  @Override
  public void contribute(Builder builder) {
    long count = tacoCount.get();
    builder.withDetail("taco-stats", count < 0
        ? Map.of("status", "pending") : Map.of("count", count));
  }

  @Scheduled(initialDelay = 0,
      fixedDelayString = "${tacocloud.info.taco-count-refresh-ms:60000}")
  public void refresh() {
    tacoRepo.count().subscribe(tacoCount::set,
        error -> log.warn("Taco count refresh failed"));
  }
}
