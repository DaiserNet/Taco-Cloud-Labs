package tacos.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "tacocloud.coupons")
@Validated
@Data
public class CouponProperties {

  @Valid
  private Map<String, CouponRule> codes = new LinkedHashMap<>();

  @Data
  public static class CouponRule {
    @NotNull
    private CouponType type;

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal value;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startsOn;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expiresOn;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = true)
    private BigDecimal minimumSubtotal = BigDecimal.ZERO;

    @DecimalMin(value = "0.00", inclusive = true)
    private BigDecimal maximumDiscount;
  }
}
