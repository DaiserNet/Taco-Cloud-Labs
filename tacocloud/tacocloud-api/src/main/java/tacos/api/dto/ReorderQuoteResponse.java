package tacos.api.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Value;

@Value
public class ReorderQuoteResponse {
  String sourceOrderId;
  String currency;
  BigDecimal originalSubtotal;
  BigDecimal quotedSubtotal;
  BigDecimal originalTotal;
  BigDecimal quotedTotal;
  BigDecimal priceDifference;
  boolean couponApplied;
  List<IngredientDifference> ingredientDifferences;
  String quoteFingerprint;
  boolean confirmationRequired;

  @Value
  public static class IngredientDifference {
    String ingredientId;
    String previousName;
    String currentName;
    BigDecimal previousUnitPrice;
    BigDecimal currentUnitPrice;
    boolean metadataChanged;
  }
}
