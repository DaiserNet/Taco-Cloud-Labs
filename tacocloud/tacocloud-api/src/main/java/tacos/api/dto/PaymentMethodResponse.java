package tacos.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PaymentMethodResponse {
  private String paymentMethodId;
  private String brand;
  private String last4;
}
