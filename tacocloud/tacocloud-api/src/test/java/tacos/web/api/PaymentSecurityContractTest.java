package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;

class PaymentSecurityContractTest {

  @Test
  void shouldRemoveRawCardFieldsFromOrderDomain() {
    Set<String> fields = fields(TacoOrder.class);

    assertFalse(fields.contains("ccNumber"));
    assertFalse(fields.contains("ccExpiration"));
    assertFalse(fields.contains("ccCVV"));
  }

  @Test
  void shouldModelOnlyTokenizedPaymentData() {
    Set<String> fields = fields(PaymentMethod.class);

    assertTrue(fields.containsAll(Arrays.asList(
        "paymentToken", "brand", "last4", "expiration")));
    assertFalse(fields.contains("ccNumber"));
    assertFalse(fields.contains("ccCVV"));
  }

  @Test
  void shouldRequirePaymentMethodIdWithoutCardFieldsInOrderRequest() {
    Set<String> fields = fields(OrderCreateRequest.class);

    assertTrue(fields.contains("paymentMethodId"));
    assertFalse(fields.contains("ccNumber"));
    assertFalse(fields.contains("ccExpiration"));
    assertFalse(fields.contains("ccCVV"));
  }

  @Test
  void shouldSerializeKitchenOrderWithoutAnyPaymentInformation() throws Exception {
    TacoOrder order = new TacoOrder();
    setIfPresent(order, "ccNumber", "4000000000000002");
    setIfPresent(order, "ccExpiration", "12/99");
    setIfPresent(order, "ccCVV", "123");
    setIfPresent(order, "paymentMethodId", "PAYMENT-ID");
    setIfPresent(order, "paymentBrand", "VISA");
    setIfPresent(order, "paymentLast4", "0002");

    String json = new ObjectMapper().writeValueAsString(order);

    assertFalse(json.contains("ccNumber"));
    assertFalse(json.contains("ccExpiration"));
    assertFalse(json.contains("ccCVV"));
    assertFalse(json.contains("paymentMethodId"));
    assertFalse(json.contains("paymentBrand"));
    assertFalse(json.contains("paymentLast4"));
    assertFalse(json.contains("4000000000000002"));
  }

  private Set<String> fields(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .map(Field::getName)
        .collect(Collectors.toSet());
  }

  private void setIfPresent(Object target, String fieldName, String value)
      throws IllegalAccessException {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (NoSuchFieldException ignored) {
      // The desired final state is that legacy raw-card fields do not exist.
    }
  }
}
