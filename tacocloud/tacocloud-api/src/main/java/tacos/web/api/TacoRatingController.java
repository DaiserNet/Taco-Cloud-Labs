package tacos.web.api;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.api.dto.TacoRankingResponse;
import tacos.api.dto.TacoRatingRequest;
import tacos.ratings.TacoRatingService;

@RestController
@RequestMapping(path = {"/api/tacos", "/api/v1/tacos"}, produces = "application/json")
public class TacoRatingController {
  private final TacoRatingService ratingService;

  public TacoRatingController(TacoRatingService ratingService) {
    this.ratingService = ratingService;
  }

  @PutMapping(path = "/{id}/rating", consumes = "application/json")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> rate(@PathVariable String id,
      @Valid @RequestBody TacoRatingRequest request,
      Authentication authentication) {
    return ratingService.rate(id, request.getScore(), authentication);
  }

  @GetMapping("/top")
  public Flux<TacoRankingResponse> top(
      @RequestParam(defaultValue = "10") int limit) {
    return ratingService.top(limit);
  }
}
