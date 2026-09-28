package tacos.web.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

import tacos.api.error.ApiExceptionHandler;
import tacos.pricing.CouponProperties;
import tacos.pricing.CouponProperties.CouponRule;
import tacos.pricing.CouponService;
import tacos.pricing.CouponType;

class CouponControllerTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    CouponProperties properties = new CouponProperties();
    properties.getCodes().put("SAVE50", percentageRule());
    Clock clock = Clock.fixed(
        Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);
    CouponService service = new CouponService(properties, clock, "USD");
    mvc = MockMvcBuilders.standaloneSetup(new CouponController(service))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void shouldQuoteCouponWithoutCreatingAnOrderOrExposingItsCode() throws Exception {
    perform(post("/api/orders/quote")
        .content("{\"subtotal\":100.00,\"couponCode\":\"save50\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.subtotal").value(100.00))
        .andExpect(jsonPath("$.discount").value(20.00))
        .andExpect(jsonPath("$.total").value(80.00))
        .andExpect(jsonPath("$.couponApplied").value(true))
        .andExpect(jsonPath("$", not(hasKey("couponCode"))))
        .andExpect(jsonPath("$", not(hasKey("codes"))));
  }

  @Test
  void shouldReturnGenericProblemForUnknownCoupon() throws Exception {
    perform(post("/api/orders/quote")
        .content("{\"subtotal\":100.00,\"couponCode\":\"UNKNOWN\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(
            MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("COUPON_NOT_APPLICABLE"))
        .andExpect(jsonPath("$.detail").value("Coupon cannot be applied."));
  }

  @Test
  void shouldNotExposeAReadableCouponCatalog() {
    boolean hasGetEndpoint = java.util.Arrays.stream(
        CouponController.class.getDeclaredMethods())
        .anyMatch(method -> method.isAnnotationPresent(GetMapping.class));

    assertFalse(hasGetEndpoint);
  }

  private ResultActions perform(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    MvcResult pending = mvc.perform(request
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(request().asyncStarted())
        .andReturn();
    return mvc.perform(asyncDispatch(pending));
  }

  private CouponRule percentageRule() {
    CouponRule rule = new CouponRule();
    rule.setType(CouponType.PERCENTAGE);
    rule.setValue(new BigDecimal("50.00"));
    rule.setStartsOn(LocalDate.of(2026, 1, 1));
    rule.setExpiresOn(LocalDate.of(2026, 12, 31));
    rule.setMinimumSubtotal(new BigDecimal("10.00"));
    rule.setMaximumDiscount(new BigDecimal("20.00"));
    return rule;
  }
}
