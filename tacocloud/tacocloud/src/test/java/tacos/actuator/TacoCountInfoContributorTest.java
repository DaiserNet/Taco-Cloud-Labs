package tacos.actuator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import reactor.core.publisher.Mono;
import tacos.data.TacoRepository;

class TacoCountInfoContributorTest {
  @Test
  void shouldReadCachedCountWithoutBlockingContributor() {
    TacoRepository tacos = mock(TacoRepository.class);
    when(tacos.count()).thenReturn(Mono.just(4L));
    TacoCountInfoContributor contributor = new TacoCountInfoContributor(tacos);
    Info.Builder pending = new Info.Builder();
    contributor.contribute(pending);
    assertEquals(Map.of("status", "pending"),
        pending.build().getDetails().get("taco-stats"));

    contributor.refresh();
    Info.Builder refreshed = new Info.Builder();
    contributor.contribute(refreshed);
    assertEquals(Map.of("count", 4L),
        refreshed.build().getDetails().get("taco-stats"));
  }
}
