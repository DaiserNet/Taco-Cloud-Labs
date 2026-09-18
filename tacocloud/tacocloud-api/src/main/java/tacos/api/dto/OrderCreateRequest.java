package tacos.api.dto;

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@Data
public class OrderCreateRequest {

  @NotBlank(message = "must not be blank")
  @Size(max = 100, message = "must contain at most 100 characters")
  private String deliveryName;

  @NotBlank(message = "must not be blank")
  @Size(max = 150, message = "must contain at most 150 characters")
  private String deliveryStreet;

  @NotBlank(message = "must not be blank")
  @Size(max = 100, message = "must contain at most 100 characters")
  private String deliveryCity;

  @NotBlank(message = "must not be blank")
  @Size(max = 50, message = "must contain at most 50 characters")
  private String deliveryState;

  @NotBlank(message = "must not be blank")
  @Pattern(regexp = "[A-Za-z0-9 -]{3,20}", message = "must be a valid postal code")
  private String deliveryZip;

  @NotBlank(message = "must not be blank")
  @Pattern(regexp = "[0-9]{13,19}", message = "must contain between 13 and 19 digits")
  private String ccNumber;

  @NotBlank(message = "must not be blank")
  @Pattern(regexp = "(0[1-9]|1[0-2])/\\d{2}", message = "must use MM/YY format")
  private String ccExpiration;

  @NotBlank(message = "must not be blank")
  @Pattern(regexp = "\\d{3,4}", message = "must contain 3 or 4 digits")
  private String ccCVV;

  @NotNull(message = "must be provided")
  @Size(min = 1, max = 50, message = "must contain between 1 and 50 tacos")
  @Valid
  private List<TacoItem> tacos;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in order creation: " + name);
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class TacoItem {
    @Size(max = 64, message = "must contain at most 64 characters")
    private String id;

    @NotBlank(message = "must not be blank")
    @Size(min = 5, max = 100, message = "must contain between 5 and 100 characters")
    private String name;

    @NotNull(message = "must be provided")
    @Size(min = 1, max = 20, message = "must contain between 1 and 20 ingredients")
    @Valid
    private List<IngredientItem> ingredients;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class IngredientItem {
    @NotBlank(message = "must not be blank")
    @Size(max = 64, message = "must contain at most 64 characters")
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "must be an alphanumeric identifier")
    private String id;
  }
}
