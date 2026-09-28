package tacos.design;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TacoDesignViolation {
  private String code;
  private String message;
}
