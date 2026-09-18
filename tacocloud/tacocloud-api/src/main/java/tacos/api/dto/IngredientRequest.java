package tacos.api.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;
import tacos.Ingredient.Type;

@Data
public class IngredientRequest {

  @NotBlank(message = "must not be blank")
  @Size(max = 64, message = "must contain at most 64 characters")
  @Pattern(regexp = "[A-Za-z0-9_-]+", message = "must be an alphanumeric identifier")
  private String id;

  @NotBlank(message = "must not be blank")
  @Size(max = 100, message = "must contain at most 100 characters")
  private String name;

  @NotNull(message = "must be provided")
  private Type type;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in ingredient request: " + name);
  }
}
