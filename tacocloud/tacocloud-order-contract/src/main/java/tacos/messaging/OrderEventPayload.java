package tacos.messaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class OrderEventPayload {
  private final String orderId;
  private final List<Item> items;

  @JsonCreator
  public OrderEventPayload(@JsonProperty("orderId") String orderId,
      @JsonProperty("items") List<Item> items) {
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.items = Collections.unmodifiableList(new ArrayList<>(
        Objects.requireNonNull(items, "items")));
  }

  public String getOrderId() { return orderId; }
  public List<Item> getItems() { return items; }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Item {
    private final String tacoName;
    private final int quantity;
    private final List<Ingredient> ingredients;

    @JsonCreator
    public Item(@JsonProperty("tacoName") String tacoName,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("ingredients") List<Ingredient> ingredients) {
      this.tacoName = Objects.requireNonNull(tacoName, "tacoName");
      if (quantity < 1) {
        throw new IllegalArgumentException("quantity must be positive");
      }
      this.quantity = quantity;
      this.ingredients = Collections.unmodifiableList(new ArrayList<>(
          Objects.requireNonNull(ingredients, "ingredients")));
    }

    public String getTacoName() { return tacoName; }
    public int getQuantity() { return quantity; }
    public List<Ingredient> getIngredients() { return ingredients; }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Ingredient {
    private final String id;
    private final String name;
    private final String type;

    @JsonCreator
    public Ingredient(@JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("type") String type) {
      this.id = Objects.requireNonNull(id, "id");
      this.name = Objects.requireNonNull(name, "name");
      this.type = Objects.requireNonNull(type, "type");
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getType() { return type; }
  }
}
