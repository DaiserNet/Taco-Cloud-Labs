package tacos.api.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

import lombok.Data;

@Data
public class TacoRatingRequest {
  @Min(1)
  @Max(5)
  private int score;
}
