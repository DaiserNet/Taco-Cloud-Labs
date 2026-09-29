package tacos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

@Data
@Document
@CompoundIndexes({
    @CompoundIndex(name = "order_owner_placed_id",
        def = "{'user._id': 1, 'placedAt': -1, '_id': 1}"),
    @CompoundIndex(name = "order_placed_id",
        def = "{'placedAt': -1, '_id': 1}"),
    @CompoundIndex(name = "order_status_placed_id",
        def = "{'status': 1, 'placedAt': -1, '_id': 1}")
})
public class TacoOrder implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  @Version
  private Long version;
  private Date placedAt = new Date();

  private User user;

  private OrderStatus status = OrderStatus.CREATED;

  @JsonIgnore
  private List<OrderStatusChange> statusHistory = new ArrayList<>();

  private String deliveryName;

  private String deliveryStreet;

  private String deliveryCity;

  private String deliveryState;

  private String deliveryZip;

  @JsonIgnore
  private String paymentMethodId;

  @JsonIgnore
  private String paymentBrand;

  @JsonIgnore
  private String paymentLast4;


  private List<OrderLine> items = new ArrayList<>();

  private String currency = "USD";

  private BigDecimal subtotal = new BigDecimal("0.00");

  @JsonIgnore
  private String couponCode;

  private boolean couponApplied;

  private BigDecimal discount = new BigDecimal("0.00");

  private BigDecimal total = new BigDecimal("0.00");

  @JsonIgnore
  private String inventoryReservationId;

  @JsonIgnore
  private List<Taco> tacos = new ArrayList<>();

  public void addItem(OrderLine item) {
    this.items.add(item);
  }

  public void addTaco(Taco design) {
    this.tacos.add(design);
}

}
