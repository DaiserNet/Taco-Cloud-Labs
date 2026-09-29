package tacos.api.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;

@Data
public class ReorderRequest {
  @NotBlank
  @Size(max = 64)
  private String paymentMethodId;

  @Size(max = 64)
  @Pattern(regexp = "[A-Za-z0-9_-]+")
  private String couponCode;

  @Pattern(regexp = "[0-9a-f]{64}")
  private String quoteFingerprint;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in reorder: " + name);
  }
}
