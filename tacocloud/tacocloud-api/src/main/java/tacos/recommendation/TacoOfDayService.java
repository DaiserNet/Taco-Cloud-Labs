package tacos.recommendation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import lombok.Value;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.data.TacoRepository;
import tacos.design.TacoDesignException;
import tacos.design.TacoDesignValidator;

@Service
public class TacoOfDayService {
  private final TacoRepository tacoRepo;
  private final TacoDesignValidator designValidator;
  private final Clock clock;
  private final ZoneId zone;

  public TacoOfDayService(TacoRepository tacoRepo,
      TacoDesignValidator designValidator, Clock clock, ZoneId zone) {
    this.tacoRepo = tacoRepo;
    this.designValidator = designValidator;
    this.clock = clock;
    this.zone = zone;
  }

  public Mono<Selection> today() {
    return Mono.defer(() -> {
      LocalDate date = clock.instant().atZone(zone).toLocalDate();
      return tacoRepo.findAll()
          .filter(taco -> StringUtils.hasText(taco.getId()))
          .concatMap(taco -> designValidator.requireValid(taco)
              .onErrorResume(TacoDesignException.class, error -> Mono.empty()))
          .collectSortedList(Comparator.comparing(Taco::getId))
          .flatMap(candidates -> {
            if (candidates.isEmpty()) {
              return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                  "No hay tacos válidos y disponibles para recomendar."));
            }
            int index = (int) Math.floorMod(date.toEpochDay(),
                (long) candidates.size());
            return Mono.just(new Selection(candidates.get(index), date,
                "Porque hoy es " + date + " y este diseño está disponible."));
          });
    });
  }

  @Value
  public static class Selection {
    Taco taco;
    LocalDate date;
    String reason;
  }
}
