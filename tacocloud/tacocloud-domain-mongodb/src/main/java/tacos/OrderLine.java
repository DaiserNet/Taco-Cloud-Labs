package tacos;

import java.io.Serializable;
import java.math.BigDecimal;

import lombok.Data;

@Data
public class OrderLine implements Serializable {
  private static final long serialVersionUID = 1L;

  private Taco taco;
  private int quantity;
  private BigDecimal unitPriceAtPurchase = new BigDecimal("0.00");
  private BigDecimal subtotal = new BigDecimal("0.00");
}
