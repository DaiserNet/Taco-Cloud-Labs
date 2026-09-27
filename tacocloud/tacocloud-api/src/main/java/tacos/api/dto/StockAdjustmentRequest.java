package tacos.api.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;

@Data
public class StockAdjustmentRequest {

  @NotNull(message = "must be provided")
  @Min(value = 0, message = "must not be negative")
  private Long expectedVersion;

  @NotNull(message = "must be provided")
  private Integer adjustment;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in stock adjustment: " + name);
  }
}
