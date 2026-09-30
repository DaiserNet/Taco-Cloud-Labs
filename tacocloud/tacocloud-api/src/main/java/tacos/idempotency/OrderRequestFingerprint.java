package tacos.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import tacos.api.dto.OrderCreateRequest;

@Component
public class OrderRequestFingerprint {
  public String hash(OrderCreateRequest request) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      add(digest, request.getDeliveryName());
      add(digest, request.getDeliveryStreet());
      add(digest, request.getDeliveryCity());
      add(digest, request.getDeliveryState());
      add(digest, request.getDeliveryZip());
      add(digest, request.getPaymentMethodId());
      add(digest, request.getCouponCode() == null ? null
          : request.getCouponCode().trim().toUpperCase(Locale.ROOT));
      List<OrderCreateRequest.OrderItem> items = request.getItems();
      add(digest, items == null ? null : String.valueOf(items.size()));
      if (items != null) {
        for (OrderCreateRequest.OrderItem item : items) {
          if (item == null) {
            add(digest, null);
            continue;
          }
          add(digest, item.getQuantity() == null ? null
              : String.valueOf(item.getQuantity()));
          OrderCreateRequest.TacoItem taco = item.getTaco();
          if (taco == null) {
            add(digest, null);
            continue;
          }
          add(digest, taco.getName());
          List<String> ingredients = taco.getIngredientIds();
          add(digest, ingredients == null ? null
              : String.valueOf(ingredients.size()));
          if (ingredients != null) {
            List<String> sorted = new ArrayList<>(ingredients);
            Collections.sort(sorted);
            for (String ingredientId : sorted) {
              add(digest, ingredientId);
            }
          }
        }
      }
      byte[] bytes = digest.digest();
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte value : bytes) {
        hex.append(Character.forDigit((value >>> 4) & 15, 16));
        hex.append(Character.forDigit(value & 15, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private void add(MessageDigest digest, String value) {
    if (value == null) {
      digest.update(new byte[] {-1, -1, -1, -1});
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(new byte[] {
        (byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16),
        (byte) (bytes.length >>> 8), (byte) bytes.length });
    digest.update(bytes);
  }
}
