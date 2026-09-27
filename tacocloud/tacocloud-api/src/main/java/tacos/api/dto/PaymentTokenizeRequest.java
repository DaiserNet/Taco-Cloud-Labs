package tacos.api.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;
import lombok.ToString;

@Data
public class PaymentTokenizeRequest {

  @NotBlank(message = "must not be blank")
  @Pattern(regexp = "[0-9]{12,19}", message = "must contain 12 to 19 digits")
  @ToString.Exclude
  private String pan;

  @NotBlank(message = "must not be blank")
  @Pattern(regexp = "(0[1-9]|1[0-2])/[0-9]{2}", message = "must use MM/YY format")
  private String expiration;

  @NotBlank(message = "must not be blank")
  @Pattern(regexp = "[0-9]{3,4}", message = "must contain 3 or 4 digits")
  @ToString.Exclude
  private String cvv;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unsupported payment tokenization field");
  }
}
