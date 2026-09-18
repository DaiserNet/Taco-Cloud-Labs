package tacos.web.api;

public abstract class EmailOrderConversionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  protected EmailOrderConversionException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
