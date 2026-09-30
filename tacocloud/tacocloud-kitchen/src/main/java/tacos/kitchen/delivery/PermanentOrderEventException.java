package tacos.kitchen.delivery;

public class PermanentOrderEventException extends RuntimeException {
  private final String code;

  public PermanentOrderEventException(String code) {
    super(code);
    this.code = code;
  }

  public String getCode() { return code; }
}
