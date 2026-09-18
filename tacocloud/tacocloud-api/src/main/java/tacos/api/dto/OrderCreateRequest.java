package tacos.api.dto;

import java.util.List;

import javax.validation.Valid;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@Data
public class OrderCreateRequest {

  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;
  private String ccNumber;
  private String ccExpiration;
  private String ccCVV;

  @Valid
  private List<TacoItem> tacos;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in order creation: " + name);
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class TacoItem {
    private String id;
    private String name;

    @Valid
    private List<IngredientItem> ingredients;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class IngredientItem {
    private String id;
  }
}
