package tacos.api.error;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiProblem {

  private String type;
  private String title;
  private int status;
  private String detail;
  private String instance;
  private String code;
  private List<Violation> violations;

  @Data
  @AllArgsConstructor
  public static class Violation {
    private String field;
    private String message;
  }
}
