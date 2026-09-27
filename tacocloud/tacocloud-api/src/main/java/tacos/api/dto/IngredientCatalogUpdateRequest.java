package tacos.api.dto;

import java.math.BigDecimal;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;

@Data
public class IngredientCatalogUpdateRequest {

  @NotNull(message = "must be provided")
  @Min(value = 0, message = "must not be negative")
  private Long expectedVersion;

  @DecimalMin(value = "0.00", inclusive = true,
      message = "must not be negative")
  @Digits(integer = 10, fraction = 4,
      message = "must contain at most 10 integer and 4 decimal digits")
  private BigDecimal unitPrice;

  private Boolean available;

  @Min(value = 0, message = "must not be negative")
  private Integer reorderLevel;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in catalog update: " + name);
  }
}
