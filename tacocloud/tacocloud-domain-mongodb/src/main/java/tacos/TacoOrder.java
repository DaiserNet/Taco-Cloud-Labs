package tacos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

@Data
@Document
public class TacoOrder implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private Date placedAt = new Date();

  private User user;

  private OrderStatus status = OrderStatus.PLACED;

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

  private BigDecimal total = new BigDecimal("0.00");

  @JsonIgnore
  private List<Taco> tacos = new ArrayList<>();

  public void addItem(OrderLine item) {
    this.items.add(item);
  }

  public void addTaco(Taco design) {
    this.tacos.add(design);
}

}
