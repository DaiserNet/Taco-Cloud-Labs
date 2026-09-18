package tacos.web.api;

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import lombok.Data;

@Data
public class EmailOrder {

  @NotBlank(message = "must not be blank")
  @Email(message = "must be a valid email address")
  @Size(max = 254, message = "must contain at most 254 characters")
  private String email;

  @NotNull(message = "must be provided")
  @Size(min = 1, max = 50, message = "must contain between 1 and 50 tacos")
  @Valid
  private List<EmailTaco> tacos;
  
  @Data
  public static class EmailTaco {
    @NotBlank(message = "must not be blank")
    @Size(min = 5, max = 100, message = "must contain between 5 and 100 characters")
    private String name;

    @NotNull(message = "must be provided")
    @Size(min = 1, max = 20, message = "must contain between 1 and 20 ingredients")
    private List<@NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must contain at most 64 characters") String> ingredients;
  }
  
}
