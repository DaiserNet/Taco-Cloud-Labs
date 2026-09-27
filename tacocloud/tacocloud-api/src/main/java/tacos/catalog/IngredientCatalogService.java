package tacos.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.api.dto.IngredientCatalogUpdateRequest;
import tacos.api.dto.StockAdjustmentRequest;
import tacos.data.IngredientRepository;

@Service
public class IngredientCatalogService {

  private final IngredientRepository ingredientRepo;

  public IngredientCatalogService(IngredientRepository ingredientRepo) {
    this.ingredientRepo = ingredientRepo;
  }

  public Mono<Ingredient> updateCatalog(
      String ingredientId, IngredientCatalogUpdateRequest request) {
    return findIngredient(ingredientId)
        .flatMap(ingredient -> {
          if (!matchesVersion(ingredient, request.getExpectedVersion())) {
            return Mono.error(new IngredientVersionConflictException());
          }
          if (request.getUnitPrice() == null && request.getAvailable() == null
              && request.getReorderLevel() == null) {
            return Mono.error(new IngredientCatalogValidationException(
                "INGREDIENT_CATALOG_EMPTY",
                "At least one catalog field must be provided."));
          }
          if (request.getUnitPrice() != null) {
            if (request.getUnitPrice().signum() < 0) {
              return Mono.error(new IngredientCatalogValidationException(
                  "INGREDIENT_PRICE_NEGATIVE",
                  "Ingredient price must not be negative."));
            }
            ingredient.setUnitPrice(request.getUnitPrice()
                .setScale(2, RoundingMode.HALF_UP));
          }
          if (request.getReorderLevel() != null) {
            if (request.getReorderLevel() < 0) {
              return Mono.error(new IngredientCatalogValidationException(
                  "INGREDIENT_REORDER_LEVEL_NEGATIVE",
                  "Reorder level must not be negative."));
            }
            ingredient.setReorderLevel(request.getReorderLevel());
          }
          if (request.getAvailable() != null) {
            ingredient.setAvailable(request.getAvailable());
          }
          if (ingredient.isAvailable() && ingredient.getStockOnHand() == 0) {
            return Mono.error(new IngredientCatalogValidationException(
                "INGREDIENT_AVAILABILITY_INVALID",
                "An available ingredient must have stock."));
          }
          return ingredientRepo.save(ingredient);
        })
        .onErrorMap(OptimisticLockingFailureException.class,
            IngredientVersionConflictException::new);
  }

  public Mono<Ingredient> adjustStock(
      String ingredientId, StockAdjustmentRequest request) {
    return findIngredient(ingredientId)
        .flatMap(ingredient -> {
          if (!matchesVersion(ingredient, request.getExpectedVersion())) {
            return Mono.error(new IngredientVersionConflictException());
          }
          if (request.getAdjustment() == null || request.getAdjustment() == 0) {
            return Mono.error(new IngredientCatalogValidationException(
                "INGREDIENT_STOCK_ADJUSTMENT_ZERO",
                "Stock adjustment must not be zero."));
          }
          long adjustedStock = (long) ingredient.getStockOnHand()
              + request.getAdjustment();
          if (adjustedStock < 0 || adjustedStock > Integer.MAX_VALUE) {
            return Mono.error(new IngredientCatalogValidationException(
                "INGREDIENT_STOCK_NEGATIVE",
                "Stock adjustment would produce an invalid stock value."));
          }
          ingredient.setStockOnHand((int) adjustedStock);
          if (adjustedStock == 0) {
            ingredient.setAvailable(false);
          }
          return ingredientRepo.save(ingredient);
        })
        .onErrorMap(OptimisticLockingFailureException.class,
            IngredientVersionConflictException::new);
  }

  private Mono<Ingredient> findIngredient(String ingredientId) {
    return ingredientRepo.findById(ingredientId)
        .switchIfEmpty(Mono.error(
            new ResponseStatusException(HttpStatus.NOT_FOUND)));
  }

  private boolean matchesVersion(Ingredient ingredient, Long expectedVersion) {
    Long currentVersion = ingredient.getVersion() == null
        ? Long.valueOf(0L) : ingredient.getVersion();
    return Objects.equals(currentVersion, expectedVersion);
  }
}
