package tacos.pricing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonIgnore;

import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;

class CouponContractTest {

  @Test
  void shouldProvideCouponEngineInActiveApiModule() throws Exception {
    assertNotNull(Class.forName("tacos.pricing.CouponProperties"));
    assertNotNull(Class.forName("tacos.pricing.CouponService"));
    assertNotNull(Class.forName("tacos.web.api.CouponController"));
  }

  @Test
  void shouldPersistCouponOutcomeWithoutExposingAppliedCode() throws Exception {
    Set<String> orderFields = fields(TacoOrder.class);
    Set<String> requestFields = fields(OrderCreateRequest.class);
    Set<String> responseFields = fields(OrderResponse.class);

    assertTrue(orderFields.containsAll(Arrays.asList(
        "couponCode", "couponApplied", "discount")));
    assertTrue(requestFields.contains("couponCode"));
    assertTrue(responseFields.containsAll(Arrays.asList(
        "couponApplied", "discount")));
    assertFalse(responseFields.contains("couponCode"));

    Field couponCode = TacoOrder.class.getDeclaredField("couponCode");
    assertNotNull(couponCode.getAnnotation(JsonIgnore.class));
  }

  private Set<String> fields(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .map(Field::getName)
        .collect(Collectors.toSet());
  }
}
