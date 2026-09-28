package tacos.inventory;

public class InsufficientStockException extends InventoryReservationException {
  private static final long serialVersionUID = 1L;

  private final String ingredientId;
  private final int requestedQuantity;

  public InsufficientStockException(String ingredientId, int requestedQuantity) {
    super("INSUFFICIENT_STOCK",
        "Insufficient stock for ingredient: " + ingredientId + ".");
    this.ingredientId = ingredientId;
    this.requestedQuantity = requestedQuantity;
  }

  public String getIngredientId() {
    return ingredientId;
  }

  public int getRequestedQuantity() {
    return requestedQuantity;
  }
}
