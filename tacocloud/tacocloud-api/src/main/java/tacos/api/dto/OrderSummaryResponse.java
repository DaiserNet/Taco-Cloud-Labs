package tacos.api.dto;

import java.math.BigDecimal;
import java.util.Date;

import lombok.Value;
import tacos.OrderStatus;

@Value
public class OrderSummaryResponse {
  String id;
  Date placedAt;
  OrderStatus status;
  BigDecimal total;
  String currency;
}
