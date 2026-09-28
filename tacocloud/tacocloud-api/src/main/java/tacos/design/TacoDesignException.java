package tacos.design;

import tacos.api.dto.TacoDesignValidationResponse;

public class TacoDesignException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final TacoDesignValidationResponse validation;

  public TacoDesignException(TacoDesignValidationResponse validation) {
    super("Taco design violates one or more rules.");
    this.validation = validation;
  }

  public TacoDesignValidationResponse getValidation() {
    return validation;
  }
}
