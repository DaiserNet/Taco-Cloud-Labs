package tacos.web.api;

public class EmailOrderUserNotFoundException extends EmailOrderConversionException {
  private static final long serialVersionUID = 1L;

  public EmailOrderUserNotFoundException() {
    super("EMAIL_ORDER_USER_NOT_FOUND", "The email order user was not found");
  }
}
