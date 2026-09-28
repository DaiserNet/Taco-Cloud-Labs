package tacos.api.dto;

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;

@Data
public class TacoCreateRequest {

  @NotBlank
  @Size(min = 5, max = 100)
  private String name;

  @NotNull
  @Size(min = 1, max = 20)
  private List<@NotNull @Valid IngredientId> ingredients;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in taco creation: " + name);
  }

  @Data
  public static class IngredientId {
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z0-9_-]+")
    private String id;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException(
          "Field not allowed in taco ingredient: " + name);
    }
  }
}
