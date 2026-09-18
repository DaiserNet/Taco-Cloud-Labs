package tacos.web.api;

public class InvalidEmailOrderException extends EmailOrderConversionException {
  private static final long serialVersionUID = 1L;

  public InvalidEmailOrderException(String detail) {
    super("INVALID_EMAIL_ORDER", detail);
  }
}
