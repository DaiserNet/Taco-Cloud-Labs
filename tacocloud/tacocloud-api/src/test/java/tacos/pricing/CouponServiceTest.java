package tacos.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.pricing.CouponProperties.CouponRule;

class CouponServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 27);

  @ParameterizedTest
  @MethodSource("discountCases")
  void shouldCalculateExactDiscountForEachCouponType(
      CouponType type, String value, String maximumDiscount,
      String subtotal, String expectedDiscount, String expectedTotal) {
    CouponRule rule = rule(type, value, TODAY.minusDays(1),
        TODAY.plusDays(1), "0.00", maximumDiscount);
    CouponService service = service("SAVE", rule, TODAY);

    StepVerifier.create(service.quote(new BigDecimal(subtotal), "SAVE"))
        .assertNext(quote -> {
          assertEquals(new BigDecimal(expectedDiscount), quote.getDiscount());
          assertEquals(new BigDecimal(expectedTotal), quote.getTotal());
        })
        .verifyComplete();
  }

  static Stream<Arguments> discountCases() {
    return Stream.of(
        Arguments.of(CouponType.PERCENTAGE, "10.00", null,
            "80.00", "8.00", "72.00"),
        Arguments.of(CouponType.FIXED, "5.00", null,
            "80.00", "5.00", "75.00"),
        Arguments.of(CouponType.PERCENTAGE, "50.00", "12.00",
            "80.00", "12.00", "68.00"),
        Arguments.of(CouponType.FIXED, "50.00", null,
            "10.00", "10.00", "0.00"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-09-20", "2026-09-30"})
  void shouldIncludeStartAndExpirationDays(String currentDate) {
    CouponRule rule = rule(CouponType.PERCENTAGE, "10.00",
        LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 30),
        "0.00", null);
    CouponService service = service(
        "BOUNDARY", rule, LocalDate.parse(currentDate));

    StepVerifier.create(service.quote(new BigDecimal("20.00"), "BOUNDARY"))
        .assertNext(quote -> assertEquals(
            new BigDecimal("18.00"), quote.getTotal()))
        .verifyComplete();
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN", "EXPIRED", "NOT_STARTED", "MINIMUM"})
  void shouldReturnSameErrorForEveryInapplicableCoupon(String scenario) {
    CouponRule rule;
    String requestedCode = "SAVE";
    BigDecimal subtotal = new BigDecimal("20.00");
    switch (scenario) {
      case "EXPIRED":
        rule = rule(CouponType.PERCENTAGE, "10.00",
            TODAY.minusDays(10), TODAY.minusDays(1), "0.00", null);
        break;
      case "NOT_STARTED":
        rule = rule(CouponType.PERCENTAGE, "10.00",
            TODAY.plusDays(1), TODAY.plusDays(10), "0.00", null);
        break;
      case "MINIMUM":
        rule = rule(CouponType.PERCENTAGE, "10.00",
            TODAY.minusDays(1), TODAY.plusDays(1), "50.00", null);
        break;
      default:
        rule = rule(CouponType.PERCENTAGE, "10.00",
            TODAY.minusDays(1), TODAY.plusDays(1), "0.00", null);
        requestedCode = "DOES_NOT_EXIST";
    }
    CouponService service = service("SAVE", rule, TODAY);

    StepVerifier.create(service.quote(subtotal, requestedCode))
        .expectErrorSatisfies(error -> {
          CouponValidationException couponError =
              (CouponValidationException) error;
          assertEquals("COUPON_NOT_APPLICABLE", couponError.getCode());
          assertEquals("Coupon cannot be applied.", couponError.getMessage());
        })
        .verify();
  }

  @Test
  void shouldNormalizeCouponCodeWithoutChangingTheCalculation() {
    CouponService service = service("SAVE10",
        rule(CouponType.PERCENTAGE, "10.00", TODAY.minusDays(1),
            TODAY.plusDays(1), "0.00", null), TODAY);

    StepVerifier.create(service.quote(new BigDecimal("20.00"), "  save10  "))
        .assertNext(quote -> {
          assertEquals("SAVE10", quote.getNormalizedCouponCode());
          assertEquals(new BigDecimal("2.00"), quote.getDiscount());
        })
        .verifyComplete();
  }

  @Test
  void shouldApplyCouponToOrderOnlyOnce() {
    CouponService service = service("SAVE10",
        rule(CouponType.PERCENTAGE, "10.00", TODAY.minusDays(1),
            TODAY.plusDays(1), "0.00", null), TODAY);
    TacoOrder order = new TacoOrder();
    order.setSubtotal(new BigDecimal("100.00"));
    order.setCouponCode("save10");

    StepVerifier.create(service.apply(order).flatMap(first -> service.apply(first)))
        .assertNext(applied -> {
          assertSame(order, applied);
          assertEquals("SAVE10", applied.getCouponCode());
          assertEquals(new BigDecimal("10.00"), applied.getDiscount());
          assertEquals(new BigDecimal("90.00"), applied.getTotal());
          assertEquals(true, applied.isCouponApplied());
        })
        .verifyComplete();
  }

  private CouponService service(
      String code, CouponRule rule, LocalDate currentDate) {
    CouponProperties properties = new CouponProperties();
    properties.getCodes().put(code, rule);
    Clock clock = Clock.fixed(
        Instant.parse(currentDate + "T12:00:00Z"), ZoneOffset.UTC);
    return new CouponService(properties, clock, "USD");
  }

  private CouponRule rule(CouponType type, String value,
      LocalDate startsOn, LocalDate expiresOn, String minimumSubtotal,
      String maximumDiscount) {
    CouponRule rule = new CouponRule();
    rule.setType(type);
    rule.setValue(new BigDecimal(value));
    rule.setStartsOn(startsOn);
    rule.setExpiresOn(expiresOn);
    rule.setMinimumSubtotal(new BigDecimal(minimumSubtotal));
    rule.setMaximumDiscount(maximumDiscount == null
        ? null : new BigDecimal(maximumDiscount));
    return rule;
  }
}
