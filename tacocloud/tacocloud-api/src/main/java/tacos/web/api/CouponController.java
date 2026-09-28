package tacos.web.api;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.OrderQuoteRequest;
import tacos.api.dto.OrderQuoteResponse;
import tacos.pricing.CouponService;

@RestController
@RequestMapping(path = "/api/orders", produces = "application/json")
public class CouponController {

  private final CouponService couponService;

  public CouponController(CouponService couponService) {
    this.couponService = couponService;
  }

  @PostMapping(path = "/quote", consumes = "application/json")
  public Mono<OrderQuoteResponse> quote(
      @Valid @RequestBody OrderQuoteRequest request) {
    return couponService.quote(request.getSubtotal(), request.getCouponCode())
        .map(quote -> new OrderQuoteResponse(
            quote.getCurrency(), quote.getSubtotal(), quote.getDiscount(),
            quote.getTotal(), true));
  }
}
