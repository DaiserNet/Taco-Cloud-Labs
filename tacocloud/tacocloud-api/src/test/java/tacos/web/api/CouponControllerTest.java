package tacos.web.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import java.util.Collections;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

import reactor.core.publisher.Mono;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.api.error.ApiExceptionHandler;
import tacos.classification.TacoClassificationService;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.design.TacoDesignTestSupport;
import tacos.pricing.CouponProperties;
import tacos.pricing.CouponProperties.CouponRule;
import tacos.pricing.CouponService;
import tacos.pricing.CouponType;

class CouponControllerTest {

  private MockMvc mvc;
  private TacoRepository tacoRepo;
  private IngredientRepository ingredientRepo;

  @BeforeEach
  void setUp() {
    CouponProperties properties = new CouponProperties();
    properties.getCodes().put("SAVE50", percentageRule());
    Clock clock = Clock.fixed(
        Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);
    CouponService service = new CouponService(properties, clock, "USD");
    tacoRepo = mock(TacoRepository.class);
    ingredientRepo = mock(IngredientRepository.class);
    mvc = MockMvcBuilders.standaloneSetup(new CouponController(service,
            tacoRepo, new TacoClassificationService(ingredientRepo),
            TacoDesignTestSupport.validator(ingredientRepo)))
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
        .andExpect(jsonPath("$", not(hasKey("classification"))))
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
  void shouldIncludeTrustedTacoClassificationInQuote() throws Exception {
    Taco taco = new Taco();
    taco.setId("TACO-QUOTE");
    taco.setName("Quote taco");
    taco.setIngredients(java.util.Arrays.asList(
        new Ingredient("WRAP", null, null), new Ingredient("SLSA", null, null)));
    Ingredient wrap = new Ingredient("WRAP", "Wrap", Ingredient.Type.WRAP);
    wrap.setAvailable(true);
    wrap.setStockOnHand(10);
    wrap.setDietaryTags(EnumSet.of(DietaryTag.VEGAN,
        DietaryTag.GLUTEN_FREE));
    wrap.setSpiceLevel(SpiceLevel.NONE);
    Ingredient salsa = new Ingredient("SLSA", "Salsa", Ingredient.Type.SAUCE);
    salsa.setAvailable(true);
    salsa.setStockOnHand(10);
    salsa.setDietaryTags(EnumSet.of(DietaryTag.VEGAN,
        DietaryTag.GLUTEN_FREE));
    salsa.setAllergens(EnumSet.of(Allergen.SESAME));
    salsa.setSpiceLevel(SpiceLevel.HOT);
    when(tacoRepo.findById("TACO-QUOTE")).thenReturn(Mono.just(taco));
    when(ingredientRepo.findById("WRAP")).thenReturn(Mono.just(wrap));
    when(ingredientRepo.findById("SLSA")).thenReturn(Mono.just(salsa));

    perform(post("/api/orders/quote")
        .content("{\"subtotal\":100.00,\"couponCode\":\"save50\","
            + "\"tacoId\":\"TACO-QUOTE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(80.00))
        .andExpect(jsonPath("$.classification.tacoId").value("TACO-QUOTE"))
        .andExpect(jsonPath("$.classification.dietaryTags[0]").value("VEGAN"))
        .andExpect(jsonPath("$.classification.allergens[0]").value("SESAME"))
        .andExpect(jsonPath("$.classification.spiceLevel").value("HOT"));
  }

  @Test
  void shouldRejectClientSuppliedClassificationInQuote() throws Exception {
    mvc.perform(post("/api/orders/quote")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"subtotal\":100.00,\"couponCode\":\"save50\","
                + "\"classification\":{\"dietaryTags\":[\"VEGAN\"]}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldValidateInlineDesignBeforeCalculatingQuote() throws Exception {
    CouponService guardedCoupon = mock(CouponService.class);
    Ingredient wrap = new Ingredient("WRAP", "Wrap", Ingredient.Type.WRAP);
    wrap.setAvailable(true);
    wrap.setStockOnHand(10);
    when(ingredientRepo.findById("WRAP")).thenReturn(Mono.just(wrap));
    mvc = MockMvcBuilders.standaloneSetup(new CouponController(guardedCoupon,
            tacoRepo, new TacoClassificationService(ingredientRepo),
            TacoDesignTestSupport.validator(ingredientRepo)))
        .setControllerAdvice(new ApiExceptionHandler()).build();

    perform(post("/api/orders/quote")
        .content("{\"subtotal\":100.00,\"couponCode\":\"save50\","
            + "\"taco\":{\"name\":\"Wrong taco\",\"ingredients\":["
            + "{\"id\":\"WRAP\"},{\"id\":\"WRAP\"}]}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TACO_DESIGN_INVALID"))
        .andExpect(jsonPath("$.violations[0].code").value("TACO_BASE_COUNT"));
    verifyNoInteractions(guardedCoupon);
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
