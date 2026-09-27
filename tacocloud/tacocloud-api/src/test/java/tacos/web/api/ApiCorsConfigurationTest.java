package tacos.web.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tacos.data.TacoRepository;

@WebMvcTest(TacoController.class)
@Import(ApiCorsConfiguration.class)
@ContextConfiguration(classes = {TacoController.class, ApiCorsConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties =
    "tacocloud.api.allowed-origins=https://frontend.example.test")
class ApiCorsConfigurationTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private TacoRepository tacoRepo;

  @Test
  void shouldAllowConfiguredOriginForApiPreflight() throws Exception {
    mvc.perform(options("/api/tacos/PROBE")
            .header("Origin", "https://frontend.example.test")
            .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin",
            "https://frontend.example.test"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  void shouldRejectUnconfiguredOriginForApiPreflight() throws Exception {
    mvc.perform(options("/api/tacos/PROBE")
            .header("Origin", "https://untrusted.example.test")
            .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
