package tacos.inventory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Document(collection = "inventoryReservation")
public class InventoryReservation {

  @Id
  private String id;
  private String idempotencyKey;
  private String orderId;
  private InventoryReservationStatus status;
  private List<InventoryReservationItem> items = new ArrayList<>();
  private List<InventoryReservationItem> reservedItems = new ArrayList<>();

  public InventoryReservation(String id, String idempotencyKey,
      String orderId, List<InventoryReservationItem> items) {
    this.id = id;
    this.idempotencyKey = idempotencyKey;
    this.orderId = orderId;
    this.status = InventoryReservationStatus.RESERVING;
    this.items = new ArrayList<>(items);
  }
}
