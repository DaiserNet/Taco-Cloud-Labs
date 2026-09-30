package tacos.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import tacos.api.dto.OrderCreateRequest;

class OrderRequestFingerprintTest {
  private final OrderRequestFingerprint fingerprint = new OrderRequestFingerprint();

  @Test
  void equivalentIngredientOrderAndCouponCaseProduceSameHash() {
    OrderCreateRequest first = request();
    first.setCouponCode("summer");
    OrderCreateRequest second = request();
    second.setCouponCode(" SUMMER ");
    second.getItems().get(0).getTaco().setIngredientIds(
        Arrays.asList("SLSA", "WRAP"));
    assertEquals(fingerprint.hash(first), fingerprint.hash(second));
  }

  @Test
  void changingPaymentOrQuantityChangesHash() {
    OrderCreateRequest first = request();
    OrderCreateRequest payment = request();
    payment.setPaymentMethodId("OTHER-PAYMENT");
    OrderCreateRequest quantity = request();
    quantity.getItems().get(0).setQuantity(2);
    assertNotEquals(fingerprint.hash(first), fingerprint.hash(payment));
    assertNotEquals(fingerprint.hash(first), fingerprint.hash(quantity));
  }

  private OrderCreateRequest request() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Alice");
    request.setDeliveryStreet("Street");
    request.setDeliveryCity("City");
    request.setDeliveryState("ST");
    request.setDeliveryZip("00000");
    request.setPaymentMethodId("PAYMENT-ID");
    OrderCreateRequest.TacoItem taco = new OrderCreateRequest.TacoItem();
    taco.setName("Valid taco");
    taco.setIngredientIds(Arrays.asList("WRAP", "SLSA"));
    OrderCreateRequest.OrderItem item = new OrderCreateRequest.OrderItem();
    item.setTaco(taco);
    item.setQuantity(1);
    request.setItems(Collections.singletonList(item));
    return request;
  }
}
