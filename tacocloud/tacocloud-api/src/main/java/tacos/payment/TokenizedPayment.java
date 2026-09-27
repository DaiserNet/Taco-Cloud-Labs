package tacos.payment;

import lombok.ToString;
import lombok.Value;

@Value
public class TokenizedPayment {
  @ToString.Exclude
  String token;
  String brand;
  String last4;
  String expiration;
}
