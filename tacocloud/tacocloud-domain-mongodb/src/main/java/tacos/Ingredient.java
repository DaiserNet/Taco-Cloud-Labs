package tacos;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import org.springframework.data.annotation.Version;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(access=AccessLevel.PRIVATE, force=true)
@Document
public class Ingredient {

  @Id
  @NotBlank
  private String id;
  @NotBlank 
  private String name;
  @NotNull 
  private Type type;

  @NotNull
  @DecimalMin(value = "0.00", inclusive = true)
  @Digits(integer = 10, fraction = 2)
  private BigDecimal unitPrice = new BigDecimal("0.00");

  private boolean available;

  @Min(0)
  private int stockOnHand;

  @Min(0)
  private int reorderLevel;

  @Version
  private Long version;

  private Set<DietaryTag> dietaryTags = EnumSet.noneOf(DietaryTag.class);

  private Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);

  private SpiceLevel spiceLevel = SpiceLevel.UNKNOWN;

  @JsonIgnore
  private Set<String> inventoryReservationIds = new HashSet<>();

  public Ingredient(String id, String name, Type type) {
    this(id, name, type, new BigDecimal("0.00"), false, 0, 0);
  }

  public Ingredient(String id, String name, Type type, BigDecimal unitPrice,
      boolean available, int stockOnHand, int reorderLevel) {
    this.id = id;
    this.name = name;
    this.type = type;
    this.unitPrice = unitPrice;
    this.available = available;
    this.stockOnHand = stockOnHand;
    this.reorderLevel = reorderLevel;
  }

  @AssertTrue(message = "available ingredients must have stock")
  @JsonIgnore
  public boolean isCatalogStateValid() {
    return !available || stockOnHand > 0;
  }

  public enum Type {
    WRAP, BOWL, PROTEIN, VEGGIES, CHEESE, SAUCE
  }

}
