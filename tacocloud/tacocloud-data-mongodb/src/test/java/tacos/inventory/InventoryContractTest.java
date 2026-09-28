package tacos.inventory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.Document;

import tacos.TacoOrder;

class InventoryContractTest {

  @Test
  void shouldProvideInternalInventoryReservationService() throws Exception {
    Class<?> service = Class.forName("tacos.inventory.InventoryService");

    assertNotNull(service.getMethod("reserve", TacoOrder.class));
    assertNotNull(service.getMethod("accept", String.class, String.class));
    assertNotNull(service.getMethod("release", String.class));
  }

  @Test
  void shouldPersistReservationIdentityStateAndOrderLink() throws Exception {
    Class<?> reservation = Class.forName("tacos.inventory.InventoryReservation");
    Set<String> fields = Arrays.stream(reservation.getDeclaredFields())
        .map(field -> field.getName())
        .collect(Collectors.toSet());

    assertNotNull(reservation.getAnnotation(Document.class));
    assertTrue(fields.contains("id"));
    assertTrue(fields.contains("idempotencyKey"));
    assertTrue(fields.contains("orderId"));
    assertTrue(fields.contains("status"));
    assertTrue(fields.contains("items"));
    assertTrue(fields.contains("reservedItems"));
  }
}
