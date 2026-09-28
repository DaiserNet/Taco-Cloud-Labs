package tacos.api.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import tacos.design.TacoDesignViolation;

@Data
@AllArgsConstructor
public class TacoDesignValidationResponse {
  private boolean valid;
  private List<TacoDesignViolation> violations;
}
