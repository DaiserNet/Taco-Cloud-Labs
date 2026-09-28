package tacos.api.dto;

import java.math.BigDecimal;

import lombok.Value;

@Value
public class OrderQuoteResponse {
  String currency;
  BigDecimal subtotal;
  BigDecimal discount;
  BigDecimal total;
  boolean couponApplied;
}
