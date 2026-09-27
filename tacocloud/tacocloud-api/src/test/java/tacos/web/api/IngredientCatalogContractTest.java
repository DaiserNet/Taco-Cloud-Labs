package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.Ingredient;
import tacos.api.dto.IngredientResponse;
import tacos.api.mapper.IngredientMapper;

class IngredientCatalogContractTest {

  @Test
  void shouldModelPriceAvailabilityStockReorderLevelAndVersion() {
    Map<String, Field> fields = Arrays.stream(Ingredient.class.getDeclaredFields())
        .collect(Collectors.toMap(Field::getName, field -> field));

    assertTrue(fields.containsKey("unitPrice"));
    assertTrue(fields.containsKey("available"));
    assertTrue(fields.containsKey("stockOnHand"));
    assertTrue(fields.containsKey("reorderLevel"));
    assertTrue(fields.containsKey("version"));
    if (fields.containsKey("unitPrice")) {
      assertEquals(BigDecimal.class, fields.get("unitPrice").getType());
    }
    if (fields.containsKey("version")) {
      assertNotNull(fields.get("version").getAnnotation(Version.class));
    }
  }

  @Test
  void shouldExposeSaleDataWithoutOperationalMetadata() throws Exception {
    IngredientResponse response = new IngredientMapper().toResponse(
        new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP));

    JsonNode json = new ObjectMapper().valueToTree(response);

    assertTrue(json.has("unitPrice"));
    assertTrue(json.has("available"));
    assertFalse(json.has("stockOnHand"));
    assertFalse(json.has("reorderLevel"));
    assertFalse(json.has("version"));
  }
}
