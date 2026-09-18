package tacos.web.api;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;
import tacos.TacoOrder;

@Data
public class OrderReplaceRequest {

  @NotBlank
  @Size(max = 100)
  private String deliveryName;

  @NotBlank
  @Size(max = 150)
  private String deliveryStreet;

  @NotBlank
  @Size(max = 100)
  private String deliveryCity;

  @NotBlank
  @Size(max = 50)
  private String deliveryState;

  @NotBlank
  @Size(max = 20)
  private String deliveryZip;

  @JsonAnySetter
  public void rejectUnknown(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in order replacement: " + name);
  }

  public void applyTo(TacoOrder order) {
    order.setDeliveryName(deliveryName);
    order.setDeliveryStreet(deliveryStreet);
    order.setDeliveryCity(deliveryCity);
    order.setDeliveryState(deliveryState);
    order.setDeliveryZip(deliveryZip);
  }
}
