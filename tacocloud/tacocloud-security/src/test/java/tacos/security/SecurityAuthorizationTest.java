package tacos.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityAuthorizationTest.AuthorizationProbeController.class)
@ContextConfiguration(classes = {
    SecurityConfig.class, SecurityAuthorizationTest.AuthorizationProbeController.class
})
class SecurityAuthorizationTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private UserDetailsService userDetailsService;

  @Test
  void shouldAllowAnonymousCatalogReads() throws Exception {
    mvc.perform(get("/api/tacos/probe")).andExpect(status().isOk());
    mvc.perform(get("/api/tacos/TACO1/classification"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/tacos/top")).andExpect(status().isOk());
    mvc.perform(get("/api/ingredients/probe")).andExpect(status().isOk());
  }

  @Test
  void shouldRejectAnonymousOrderCreationWithUnauthorized() throws Exception {
    mvc.perform(post("/api/orders").with(csrf()).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/payment-methods/tokenize").with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldAllowUserOrderAccess() throws Exception {
    mvc.perform(get("/api/orders/me")).andExpect(status().isOk());
    mvc.perform(get("/api/orders/me/OWN")).andExpect(status().isOk());
    mvc.perform(post("/api/orders").with(csrf())).andExpect(status().isOk());
    mvc.perform(post("/api/orders/me/OWN/reorder/quote").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/orders/me/OWN/reorder").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/payment-methods/tokenize").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/tacos/validate").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldRequireCsrfForAuthenticatedWrites() throws Exception {
    mvc.perform(post("/api/orders")).andExpect(status().isForbidden());
    mvc.perform(post("/api/orders/me/OWN/reorder/quote"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/orders/me/OWN/reorder"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/tacos/validate")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldAllowOwnRatingWithCsrfButNotCatalogAdministration() throws Exception {
    mvc.perform(put("/api/tacos/TACO1/rating").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(put("/api/tacos/TACO1/rating"))
        .andExpect(status().isForbidden());
    mvc.perform(put("/api/tacos/TACO1").with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldRejectAnonymousRating() throws Exception {
    mvc.perform(put("/api/tacos/TACO1/rating").with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void shouldNotGiveAdminUserRatingWithoutUserRole() throws Exception {
    mvc.perform(put("/api/tacos/TACO1/rating").with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldAllowOnlyOwnFavoriteRoutesWithCsrf() throws Exception {
    mvc.perform(get("/api/users/me/favorites")).andExpect(status().isOk());
    mvc.perform(put("/api/users/me/favorites/T1").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/users/me/favorites/T1").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(put("/api/users/me/favorites/T1"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/users/bob/favorites"))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldRequireAuthenticationForFavorites() throws Exception {
    mvc.perform(get("/api/users/me/favorites"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void shouldNotGrantFavoritesToAdminWithoutUserRole() throws Exception {
    mvc.perform(get("/api/users/me/favorites"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void shouldDenyAdminReorderWithoutUserRole() throws Exception {
    mvc.perform(post("/api/orders/me/OWN/reorder/quote").with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/orders/me/OWN/reorder").with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldRequireAuthenticationForOrderWorkflow() throws Exception {
    mvc.perform(post("/api/orders/OWN/cancel").with(csrf()))
        .andExpect(status().isUnauthorized());
    mvc.perform(patch("/api/orders/OWN/status").with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldAllowOwnerCancellationButNotOperationalStatus() throws Exception {
    mvc.perform(post("/api/orders/OWN/cancel").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/orders/OWN/cancel"))
        .andExpect(status().isForbidden());
    mvc.perform(patch("/api/orders/OWN/status").with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "KITCHEN")
  void shouldAllowKitchenStatusWithCsrfButNotCancellation() throws Exception {
    mvc.perform(patch("/api/kitchen/orders/OWN/status").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(patch("/api/kitchen/orders/OWN/status"))
        .andExpect(status().isForbidden());
    mvc.perform(patch("/api/orders/OWN/status").with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/orders/OWN/cancel").with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void shouldAllowAdminStatusWithoutGivingOwnerCancellation() throws Exception {
    mvc.perform(patch("/api/orders/OWN/status").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/orders/OWN/cancel").with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldForbidUserFromAdministrationAndKitchen() throws Exception {
    mvc.perform(post("/api/ingredients").with(csrf())).andExpect(status().isForbidden());
    mvc.perform(post("/api/tacos").with(csrf())).andExpect(status().isForbidden());
    mvc.perform(get("/api/kitchen/queue")).andExpect(status().isForbidden());
    mvc.perform(post("/api/kitchen/claim").with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(patch("/api/admin/ingredients/FLTO/catalog").with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/ingredients/FLTO/stock-adjustments").with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void shouldAllowAdminIngredientManagementAndOrderAudit() throws Exception {
    mvc.perform(post("/api/ingredients").with(csrf())).andExpect(status().isOk());
    mvc.perform(patch("/api/admin/ingredients/FLTO/catalog").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/admin/ingredients/FLTO/stock-adjustments").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/orders")).andExpect(status().isOk());
    mvc.perform(get("/api/admin/orders/ANY")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldKeepOrderHistoryPrivateAndRetireGlobalList() throws Exception {
    mvc.perform(get("/api/admin/orders")).andExpect(status().isForbidden());
    mvc.perform(get("/api/orders")).andExpect(status().isForbidden());
    mvc.perform(get("/api/orders/OTHER")).andExpect(status().isForbidden());
  }

  @Test
  void shouldRequireAuthenticationForOrderHistory() throws Exception {
    mvc.perform(get("/api/orders/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/orders")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/orders/me/OWN/reorder/quote").with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void shouldRequireCsrfForAdminCatalogWrites() throws Exception {
    mvc.perform(patch("/api/admin/ingredients/FLTO/catalog"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/ingredients/FLTO/stock-adjustments"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "KITCHEN")
  void shouldAllowKitchenQueueWithoutIngredientAdministration() throws Exception {
    mvc.perform(get("/api/kitchen/queue")).andExpect(status().isOk());
    mvc.perform(get("/api/kitchen/ui")).andExpect(status().isOk());
    mvc.perform(post("/api/kitchen/claim").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/kitchen/claim"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/ingredients").with(csrf())).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldDenyUnlistedRouteByDefault() throws Exception {
    mvc.perform(get("/unlisted")).andExpect(status().isForbidden());
  }

  @Test
  void shouldProtectDataRestAndActuatorWhileKeepingHealthPublic() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/data-api/users").with(user("user").roles("USER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/actuator/info").with(user("user").roles("USER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/data-api/users").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
    mvc.perform(get("/actuator/info").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
  }

  @RestController
  static class AuthorizationProbeController {

    @GetMapping({"/api/tacos/probe", "/api/tacos/top",
        "/api/tacos/TACO1/classification",
        "/api/ingredients/probe", "/api/orders", "/api/orders/me",
        "/api/orders/me/OWN", "/api/orders/OTHER",
        "/api/admin/orders", "/api/admin/orders/ANY",
        "/api/kitchen/queue", "/api/kitchen/ui",
        "/api/users/me/favorites", "/api/users/bob/favorites",
        "/data-api/users", "/actuator/health", "/actuator/info", "/unlisted"})
    String read() {
      return "ok";
    }

    @PostMapping({"/api/orders", "/api/ingredients", "/api/kitchen/claim",
        "/api/tacos/validate", "/api/tacos",
        "/api/orders/me/OWN/reorder/quote", "/api/orders/me/OWN/reorder",
        "/api/orders/OWN/cancel",
        "/api/payment-methods/tokenize",
        "/api/admin/ingredients/FLTO/stock-adjustments"})
    String write() {
      return "ok";
    }

    @PatchMapping("/api/admin/ingredients/FLTO/catalog")
    String patchCatalog() {
      return "ok";
    }

    @PatchMapping("/api/orders/OWN/status")
    String patchOrderStatus() {
      return "ok";
    }

    @PatchMapping("/api/kitchen/orders/OWN/status")
    String patchKitchenStatus() {
      return "ok";
    }

    @org.springframework.web.bind.annotation.PutMapping(
        "/api/users/me/favorites/T1")
    String addFavorite() {
      return "ok";
    }

    @org.springframework.web.bind.annotation.DeleteMapping(
        "/api/users/me/favorites/T1")
    String deleteFavorite() {
      return "ok";
    }

    @org.springframework.web.bind.annotation.PutMapping(
        "/api/tacos/TACO1/rating")
    String rateTaco() {
      return "ok";
    }
  }
}
