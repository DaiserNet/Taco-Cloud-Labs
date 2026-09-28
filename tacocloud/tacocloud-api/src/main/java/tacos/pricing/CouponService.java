package tacos.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Currency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.pricing.CouponProperties.CouponRule;

@Service
public class CouponService {

  private static final int MONEY_SCALE = 2;
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  private final Map<String, CouponRule> rules;
  private final Clock clock;
  private final String currency;

  public CouponService(CouponProperties properties, Clock clock,
      @Value("${tacocloud.order-pricing.currency:USD}") String currency) {
    this.rules = normalizedRules(properties.getCodes());
    this.clock = clock;
    this.currency = Currency.getInstance(currency).getCurrencyCode();
  }

  public Mono<CouponQuote> quote(BigDecimal subtotal, String couponCode) {
    return Mono.defer(() -> {
      BigDecimal normalizedSubtotal = money(subtotal);
      if (normalizedSubtotal.signum() < 0) {
        return Mono.error(new CouponValidationException());
      }

      String normalizedCode = normalize(couponCode);
      CouponRule rule = rules.get(normalizedCode);
      LocalDate today = LocalDate.now(clock);
      if (rule == null || today.isBefore(rule.getStartsOn())
          || today.isAfter(rule.getExpiresOn())
          || normalizedSubtotal.compareTo(rule.getMinimumSubtotal()) < 0) {
        return Mono.error(new CouponValidationException());
      }

      BigDecimal discount = discount(rule, normalizedSubtotal);
      BigDecimal total = normalizedSubtotal.subtract(discount)
          .max(BigDecimal.ZERO)
          .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      return Mono.just(new CouponQuote(normalizedCode, currency,
          normalizedSubtotal, discount, total));
    });
  }

  public Mono<TacoOrder> apply(TacoOrder order) {
    return Mono.defer(() -> {
      if (order == null) {
        return Mono.error(new IllegalArgumentException("Order is required."));
      }
      if (order.isCouponApplied()) {
        return Mono.just(order);
      }

      BigDecimal subtotal = money(order.getSubtotal());
      if (!StringUtils.hasText(order.getCouponCode())) {
        order.setCouponCode(null);
        order.setCouponApplied(false);
        order.setDiscount(money(BigDecimal.ZERO));
        order.setTotal(subtotal);
        return Mono.just(order);
      }

      return quote(subtotal, order.getCouponCode())
          .map(quote -> {
            order.setCouponCode(quote.getNormalizedCouponCode());
            order.setCouponApplied(true);
            order.setDiscount(quote.getDiscount());
            order.setTotal(quote.getTotal());
            return order;
          });
    });
  }

  private BigDecimal discount(CouponRule rule, BigDecimal subtotal) {
    BigDecimal calculated = rule.getType() == CouponType.PERCENTAGE
        ? subtotal.multiply(rule.getValue())
            .divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP)
        : rule.getValue();
    if (rule.getMaximumDiscount() != null) {
      calculated = calculated.min(rule.getMaximumDiscount());
    }
    return calculated.min(subtotal)
        .max(BigDecimal.ZERO)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private Map<String, CouponRule> normalizedRules(
      Map<String, CouponRule> configuredRules) {
    Map<String, CouponRule> normalized = new LinkedHashMap<>();
    if (configuredRules == null) {
      return Collections.emptyMap();
    }
    configuredRules.forEach((code, rule) -> {
      String normalizedCode = normalize(code);
      validateRule(normalizedCode, rule);
      if (normalized.put(normalizedCode, rule) != null) {
        throw new IllegalArgumentException(
            "Duplicate coupon code after normalization: " + normalizedCode);
      }
    });
    return Collections.unmodifiableMap(normalized);
  }

  private void validateRule(String code, CouponRule rule) {
    if (!StringUtils.hasText(code) || rule == null || rule.getType() == null
        || rule.getValue() == null || rule.getValue().signum() <= 0
        || rule.getStartsOn() == null || rule.getExpiresOn() == null
        || rule.getStartsOn().isAfter(rule.getExpiresOn())
        || rule.getMinimumSubtotal() == null
        || rule.getMinimumSubtotal().signum() < 0
        || (rule.getMaximumDiscount() != null
            && rule.getMaximumDiscount().signum() < 0)) {
      throw new IllegalArgumentException("Invalid coupon configuration: " + code);
    }
  }

  private String normalize(String code) {
    return StringUtils.hasText(code)
        ? code.trim().toUpperCase(Locale.ROOT) : "";
  }

  private BigDecimal money(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }
}
