package tacos.web.api;

public class EmailOrderIngredientNotFoundException extends EmailOrderConversionException {
  private static final long serialVersionUID = 1L;

  private final String ingredientId;

  public EmailOrderIngredientNotFoundException(String ingredientId) {
    super("EMAIL_ORDER_INGREDIENT_NOT_FOUND", "Unknown ingredient: " + ingredientId);
    this.ingredientId = ingredientId;
  }

  public String getIngredientId() {
    return ingredientId;
  }
}
