package tacos.api.dto;

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;

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
  @Size(max = 64, message = "must contain at most 64 characters")
  private String paymentMethodId;

  @NotNull(message = "must be provided")
  @Size(min = 1, max = 50, message = "must contain between 1 and 50 items")
  @Valid
  private List<OrderItem> items;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in order creation: " + name);
  }

  @Data
  public static class OrderItem {
    @NotNull(message = "must be provided")
    @Valid
    private TacoItem taco;

    @NotNull(message = "must be provided")
    @Min(value = 1, message = "must be at least 1")
    private Integer quantity;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("Field not allowed in order item: " + name);
    }
  }

  @Data
  public static class TacoItem {
    @NotBlank(message = "must not be blank")
    @Size(min = 5, max = 100, message = "must contain between 5 and 100 characters")
    private String name;

    @NotNull(message = "must be provided")
    @Size(min = 1, max = 20, message = "must contain between 1 and 20 ingredients")
    private List<@NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must contain at most 64 characters")
        @Pattern(regexp = "[A-Za-z0-9_-]+",
            message = "must be an alphanumeric identifier") String> ingredientIds;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("Field not allowed in taco item: " + name);
    }
  }
}
