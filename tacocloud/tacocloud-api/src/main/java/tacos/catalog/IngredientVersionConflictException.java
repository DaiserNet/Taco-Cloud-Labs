package tacos.catalog;

public class IngredientVersionConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public IngredientVersionConflictException() {
    super("The ingredient was modified by another request.");
  }

  public IngredientVersionConflictException(Throwable cause) {
    super("The ingredient was modified by another request.", cause);
  }
}
