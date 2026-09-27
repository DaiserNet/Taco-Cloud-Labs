package tacos.web.api;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.IngredientAdminResponse;
import tacos.api.dto.IngredientCatalogUpdateRequest;
import tacos.api.dto.StockAdjustmentRequest;
import tacos.api.mapper.IngredientMapper;
import tacos.catalog.IngredientCatalogService;

@RestController
@RequestMapping(path = "/api/admin/ingredients", produces = "application/json")
public class IngredientAdminController {

  private final IngredientCatalogService catalogService;
  private final IngredientMapper ingredientMapper;

  public IngredientAdminController(IngredientCatalogService catalogService,
      IngredientMapper ingredientMapper) {
    this.catalogService = catalogService;
    this.ingredientMapper = ingredientMapper;
  }

  @PatchMapping(path = "/{id}/catalog", consumes = "application/json")
  public Mono<IngredientAdminResponse> updateCatalog(
      @PathVariable String id,
      @Valid @RequestBody IngredientCatalogUpdateRequest request) {
    return catalogService.updateCatalog(id, request)
        .map(ingredientMapper::toAdminResponse);
  }

  @PostMapping(path = "/{id}/stock-adjustments", consumes = "application/json")
  public Mono<IngredientAdminResponse> adjustStock(
      @PathVariable String id,
      @Valid @RequestBody StockAdjustmentRequest request) {
    return catalogService.adjustStock(id, request)
        .map(ingredientMapper::toAdminResponse);
  }
}
