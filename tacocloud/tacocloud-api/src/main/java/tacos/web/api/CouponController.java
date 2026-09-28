package tacos.web.api;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.api.dto.OrderQuoteRequest;
import tacos.api.dto.OrderQuoteResponse;
import tacos.api.dto.TacoClassification;
import tacos.classification.TacoClassificationService;
import tacos.data.TacoRepository;
import tacos.pricing.CouponService;

@RestController
@RequestMapping(path = "/api/orders", produces = "application/json")
public class CouponController {

  private final CouponService couponService;
  private final TacoRepository tacoRepo;
  private final TacoClassificationService classificationService;

  public CouponController(CouponService couponService,
      TacoRepository tacoRepo,
      TacoClassificationService classificationService) {
    this.couponService = couponService;
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService;
  }

  @PostMapping(path = "/quote", consumes = "application/json")
  public Mono<OrderQuoteResponse> quote(
      @Valid @RequestBody OrderQuoteRequest request) {
    Mono<TacoClassification> classification = StringUtils.hasText(request.getTacoId())
        ? tacoRepo.findById(request.getTacoId())
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMap(classificationService::resolveIngredients)
            .map(classificationService::classify)
        : Mono.empty();
    return couponService.quote(request.getSubtotal(), request.getCouponCode())
        .flatMap(quote -> classification
            .map(result -> new OrderQuoteResponse(
                quote.getCurrency(), quote.getSubtotal(), quote.getDiscount(),
                quote.getTotal(), true, result))
            .defaultIfEmpty(new OrderQuoteResponse(
                quote.getCurrency(), quote.getSubtotal(), quote.getDiscount(),
                quote.getTotal(), true)));
  }
}
