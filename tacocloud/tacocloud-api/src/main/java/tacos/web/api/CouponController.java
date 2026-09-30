package tacos.web.api;

import java.util.Optional;

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
import tacos.design.TacoDesignValidator;
import tacos.data.TacoRepository;
import tacos.pricing.CouponService;

@RestController
@RequestMapping(path = {"/api/orders", "/api/v1/orders"}, produces = "application/json")
public class CouponController {

  private final CouponService couponService;
  private final TacoRepository tacoRepo;
  private final TacoClassificationService classificationService;
  private final TacoDesignValidator designValidator;

  public CouponController(CouponService couponService,
      TacoRepository tacoRepo,
      TacoClassificationService classificationService,
      TacoDesignValidator designValidator) {
    this.couponService = couponService;
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService;
    this.designValidator = designValidator;
  }

  @PostMapping(path = "/quote", consumes = "application/json")
  public Mono<OrderQuoteResponse> quote(
      @Valid @RequestBody OrderQuoteRequest request) {
    if (StringUtils.hasText(request.getTacoId()) && request.getTaco() != null) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
    }
    Mono<TacoClassification> classification = StringUtils.hasText(request.getTacoId())
        ? tacoRepo.findById(request.getTacoId())
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMap(designValidator::requireValid)
            .map(classificationService::classify)
        : request.getTaco() == null ? Mono.empty()
            : designValidator.requireValid(request.getTaco())
                .map(classificationService::classify);
    return classification.map(Optional::of).defaultIfEmpty(Optional.empty())
        .flatMap(result -> couponService.quote(
            request.getSubtotal(), request.getCouponCode())
            .map(quote -> new OrderQuoteResponse(
                quote.getCurrency(), quote.getSubtotal(), quote.getDiscount(),
                quote.getTotal(), true, result.orElse(null))));
  }
}
