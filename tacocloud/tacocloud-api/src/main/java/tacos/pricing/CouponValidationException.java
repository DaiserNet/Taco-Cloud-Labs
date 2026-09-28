package tacos.pricing;

public class CouponValidationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public CouponValidationException() {
    super("Coupon cannot be applied.");
  }

  public String getCode() {
    return "COUPON_NOT_APPLICABLE";
  }
}
