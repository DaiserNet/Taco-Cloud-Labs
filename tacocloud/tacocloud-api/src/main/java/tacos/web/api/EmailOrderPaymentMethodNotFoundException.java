package tacos.web.api;

public class EmailOrderPaymentMethodNotFoundException extends EmailOrderConversionException {
  private static final long serialVersionUID = 1L;

  public EmailOrderPaymentMethodNotFoundException() {
    super("EMAIL_ORDER_PAYMENT_METHOD_NOT_FOUND",
        "The email order payment method was not found");
  }
}
