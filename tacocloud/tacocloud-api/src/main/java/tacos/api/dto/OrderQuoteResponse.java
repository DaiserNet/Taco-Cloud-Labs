package tacos.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Value;

@Value
public class OrderQuoteResponse {
  String currency;
  BigDecimal subtotal;
  BigDecimal discount;
  BigDecimal total;
  boolean couponApplied;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  TacoClassification classification;

  public OrderQuoteResponse(String currency, BigDecimal subtotal,
      BigDecimal discount, BigDecimal total, boolean couponApplied) {
    this(currency, subtotal, discount, total, couponApplied, null);
  }

  public OrderQuoteResponse(String currency, BigDecimal subtotal,
      BigDecimal discount, BigDecimal total, boolean couponApplied,
      TacoClassification classification) {
    this.currency = currency;
    this.subtotal = subtotal;
    this.discount = discount;
    this.total = total;
    this.couponApplied = couponApplied;
    this.classification = classification;
  }
}
