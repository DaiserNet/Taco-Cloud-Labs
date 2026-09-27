package tacos.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.api.dto.IngredientCatalogUpdateRequest;
import tacos.api.dto.StockAdjustmentRequest;
import tacos.data.IngredientRepository;

class IngredientCatalogServiceTest {

  private IngredientRepository ingredientRepo;
  private IngredientCatalogService service;

  @BeforeEach
  void setUp() {
    ingredientRepo = mock(IngredientRepository.class);
    service = new IngredientCatalogService(ingredientRepo);
  }

  @Test
  void shouldRoundPriceWithBigDecimalAndUpdateAtExpectedVersion() {
    Ingredient ingredient = ingredient(10, true, 3L);
    IngredientCatalogUpdateRequest request = catalogRequest(3L);
    request.setUnitPrice(new BigDecimal("1.235"));
    request.setReorderLevel(4);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));
    when(ingredientRepo.save(ingredient)).thenReturn(Mono.just(ingredient));

    StepVerifier.create(service.updateCatalog("FLTO", request))
        .assertNext(updated -> {
          assertEquals(new BigDecimal("1.24"), updated.getUnitPrice());
          assertEquals(4, updated.getReorderLevel());
        })
        .verifyComplete();

    verify(ingredientRepo).save(ingredient);
  }

  @Test
  void shouldRejectAdjustmentThatWouldProduceNegativeStock() {
    Ingredient ingredient = ingredient(2, true, 3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));

    StepVerifier.create(service.adjustStock("FLTO", stockRequest(3L, -3)))
        .expectErrorMatches(error -> error instanceof IngredientCatalogValidationException
            && "INGREDIENT_STOCK_NEGATIVE".equals(
                ((IngredientCatalogValidationException) error).getCode()))
        .verify();

    verify(ingredientRepo, never()).save(any(Ingredient.class));
  }

  @Test
  void shouldRejectStaleExpectedVersionBeforeSaving() {
    Ingredient ingredient = ingredient(10, true, 3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));

    StepVerifier.create(service.updateCatalog("FLTO", catalogRequest(2L)))
        .expectError(IngredientVersionConflictException.class)
        .verify();

    verify(ingredientRepo, never()).save(any(Ingredient.class));
  }

  @Test
  void shouldTranslateRepositoryOptimisticLockFailureToConflict() {
    Ingredient ingredient = ingredient(10, true, 3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));
    when(ingredientRepo.save(ingredient)).thenReturn(Mono.error(
        new OptimisticLockingFailureException("stale version")));

    StepVerifier.create(service.adjustStock("FLTO", stockRequest(3L, 1)))
        .expectError(IngredientVersionConflictException.class)
        .verify();
  }

  @Test
  void shouldDisableAvailabilityWhenStockReachesZero() {
    Ingredient ingredient = ingredient(2, true, 3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));
    when(ingredientRepo.save(ingredient)).thenReturn(Mono.just(ingredient));

    StepVerifier.create(service.adjustStock("FLTO", stockRequest(3L, -2)))
        .assertNext(updated -> {
          assertEquals(0, updated.getStockOnHand());
          assertFalse(updated.isAvailable());
        })
        .verifyComplete();
  }

  @Test
  void shouldRejectAvailabilityWhenStockIsZero() {
    Ingredient ingredient = ingredient(0, false, 3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));

    StepVerifier.create(service.updateCatalog("FLTO", catalogRequest(3L)))
        .expectErrorMatches(error -> error instanceof IngredientCatalogValidationException
            && "INGREDIENT_AVAILABILITY_INVALID".equals(
                ((IngredientCatalogValidationException) error).getCode()))
        .verify();

    verify(ingredientRepo, never()).save(any(Ingredient.class));
  }

  private Ingredient ingredient(int stock, boolean available, Long version) {
    Ingredient ingredient = new Ingredient("FLTO", "Flour Tortilla",
        Ingredient.Type.WRAP, new BigDecimal("0.50"), available, stock, 2);
    ingredient.setVersion(version);
    return ingredient;
  }

  private IngredientCatalogUpdateRequest catalogRequest(Long version) {
    IngredientCatalogUpdateRequest request = new IngredientCatalogUpdateRequest();
    request.setExpectedVersion(version);
    request.setAvailable(Boolean.TRUE);
    return request;
  }

  private StockAdjustmentRequest stockRequest(Long version, int adjustment) {
    StockAdjustmentRequest request = new StockAdjustmentRequest();
    request.setExpectedVersion(version);
    request.setAdjustment(adjustment);
    return request;
  }
}
