package tacos;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Document
@Data
@NoArgsConstructor(force=true, access=AccessLevel.PRIVATE)
public class PaymentMethod {

  @Id
  private String id;
  
  private final User user;

  @JsonIgnore
  @ToString.Exclude
  private final String paymentToken;

  private final String brand;
  private final String last4;
  private final String expiration;

  @PersistenceConstructor
  public PaymentMethod(User user, String paymentToken, String brand,
      String last4, String expiration) {
    this.user = user;
    this.paymentToken = paymentToken;
    this.brand = brand;
    this.last4 = last4;
    this.expiration = expiration;
  }
  
}
