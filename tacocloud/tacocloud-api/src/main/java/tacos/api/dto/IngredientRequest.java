package tacos.api.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;
import tacos.Ingredient.Type;

@Data
public class IngredientRequest {

  @NotBlank
  private String id;

  @NotBlank
  private String name;

  @NotNull
  private Type type;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in ingredient request: " + name);
  }
}
