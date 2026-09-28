package tacos.api.dto;

import java.math.BigDecimal;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;

@Data
public class OrderQuoteRequest {

  @NotNull(message = "must be provided")
  @DecimalMin(value = "0.00", inclusive = true,
      message = "must not be negative")
  @Digits(integer = 10, fraction = 2,
      message = "must contain at most 10 integer and 2 decimal digits")
  private BigDecimal subtotal;

  @NotBlank(message = "must not be blank")
  @Size(max = 64, message = "must contain at most 64 characters")
  @Pattern(regexp = "[A-Za-z0-9_-]+",
      message = "must be an alphanumeric code")
  private String couponCode;

  @Size(max = 64, message = "must contain at most 64 characters")
  @Pattern(regexp = "[A-Za-z0-9_-]+",
      message = "must be an alphanumeric identifier")
  private String tacoId;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in order quote: " + name);
  }
}
