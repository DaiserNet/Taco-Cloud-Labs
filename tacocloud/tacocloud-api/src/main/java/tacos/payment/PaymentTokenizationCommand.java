package tacos.payment;

import lombok.ToString;
import lombok.Value;

@Value
public class PaymentTokenizationCommand {
  @ToString.Exclude
  String pan;
  String expiration;
  @ToString.Exclude
  String cvv;
}
