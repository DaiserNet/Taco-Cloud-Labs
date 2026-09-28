package tacos.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    mvc.perform(get("/api/orders")).andExpect(status().isOk());
    mvc.perform(post("/api/orders").with(csrf())).andExpect(status().isOk());
    mvc.perform(post("/api/payment-methods/tokenize").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldRequireCsrfForAuthenticatedWrites() throws Exception {
    mvc.perform(post("/api/orders")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "USER")
  void shouldForbidUserFromAdministrationAndKitchen() throws Exception {
    mvc.perform(post("/api/ingredients").with(csrf())).andExpect(status().isForbidden());
    mvc.perform(post("/api/kitchen/queue").with(csrf())).andExpect(status().isForbidden());
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
    mvc.perform(get("/api/orders")).andExpect(status().isOk());
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
    mvc.perform(post("/api/kitchen/queue").with(csrf())).andExpect(status().isOk());
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

    @GetMapping({"/api/tacos/probe", "/api/tacos/TACO1/classification",
        "/api/ingredients/probe", "/api/orders",
        "/data-api/users", "/actuator/health", "/actuator/info", "/unlisted"})
    String read() {
      return "ok";
    }

    @PostMapping({"/api/orders", "/api/ingredients", "/api/kitchen/queue",
        "/api/payment-methods/tokenize",
        "/api/admin/ingredients/FLTO/stock-adjustments"})
    String write() {
      return "ok";
    }

    @PatchMapping("/api/admin/ingredients/FLTO/catalog")
    String patchCatalog() {
      return "ok";
    }
  }
}
