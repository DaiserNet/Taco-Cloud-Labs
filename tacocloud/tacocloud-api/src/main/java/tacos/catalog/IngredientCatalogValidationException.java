package tacos.catalog;

public class IngredientCatalogValidationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public IngredientCatalogValidationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
