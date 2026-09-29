package tacos.api.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;
import tacos.OrderStatus;

@Data
public class OrderStatusRequest {
  @NotNull
  private OrderStatus status;

  @NotNull
  private Long expectedVersion;

  @NotBlank
  @Size(max = 200)
  private String reason;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in status change: " + name);
  }
}
