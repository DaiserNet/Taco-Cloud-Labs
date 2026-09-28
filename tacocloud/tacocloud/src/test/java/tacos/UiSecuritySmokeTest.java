package tacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;

import javax.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StreamUtils;

import reactor.core.publisher.Flux;
import tacos.data.UserRepository;

@SpringBootTest(properties = {
    "spring.data.mongodb.port=0",
    "spring.data.mongodb.database=tc11-ui-smoke",
    "spring.mongodb.embedded.version=3.5.5",
    "spring.boot.admin.client.enabled=false"
})
@AutoConfigureMockMvc
class UiSecuritySmokeTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private UserRepository userRepo;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  void shouldServeRealAngularEntryBundlesAndAssetsWithoutOpeningOtherRoutes()
      throws Exception {
    MvcResult entry = mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andReturn();
    assertEquals("index.html", entry.getResponse().getForwardedUrl());
    String page = StreamUtils.copyToString(
        new ClassPathResource("static/index.html").getInputStream(),
        StandardCharsets.UTF_8);
    assertTrue(page.contains("<base href=\"/ui\">"));

    for (String bundle : new String[] {"inline", "polyfills", "styles",
        "vendor", "main"}) {
      mvc.perform(get("/" + bundle + ".bundle.js"))
          .andExpect(status().isOk());
    }
    mvc.perform(get("/assets/TacoCloud.png")).andExpect(status().isOk());

    Resource[] images = new PathMatchingResourcePatternResolver()
        .getResources("classpath*:static/Cloud_sm.*.png");
    assertTrue(images.length > 0);
    mvc.perform(get("/" + images[0].getFilename()))
        .andExpect(status().isOk());

    mvc.perform(get("/route-not-declared").header(
        HttpHeaders.AUTHORIZATION, basicHabuma()))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldAuthenticateSeedUserAndKeepAdminOperationsForbidden()
      throws Exception {
    User habuma = seedUser();
    assertNotNull(habuma);
    assertTrue(passwordEncoder.matches("password", habuma.getPassword()));
    assertEquals(Collections.singleton("ROLE_USER"), habuma.getAuthorities()
        .stream().map(authority -> authority.getAuthority())
        .collect(java.util.stream.Collectors.toSet()));

    MvcResult catalog = mvc.perform(get("/api/ingredients"))
        .andExpect(request().asyncStarted()).andReturn();
    mvc.perform(asyncDispatch(catalog)).andExpect(status().isOk());

    MvcResult orders = mvc.perform(get("/api/orders")
            .header(HttpHeaders.AUTHORIZATION, basicHabuma()))
        .andExpect(request().asyncStarted()).andReturn();
    mvc.perform(asyncDispatch(orders)).andExpect(status().isOk());

    Cookie csrf = csrfCookie();
    mvc.perform(post("/api/tacos")
            .header(HttpHeaders.AUTHORIZATION, basicHabuma())
            .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"catalog write\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldRequireCsrfAndAllowUserPaymentWriteWithValidToken()
      throws Exception {
    seedUser();
    String payment = "{\"pan\":\"4111111111111111\","
        + "\"expiration\":\"10/30\",\"cvv\":\"123\"}";

    mvc.perform(post("/api/payment-methods/tokenize")
            .header(HttpHeaders.AUTHORIZATION, basicHabuma())
            .contentType(MediaType.APPLICATION_JSON).content(payment))
        .andExpect(status().isForbidden());

    Cookie csrf = csrfCookie();
    MvcResult tokenized = mvc.perform(post("/api/payment-methods/tokenize")
            .header(HttpHeaders.AUTHORIZATION, basicHabuma())
            .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
            .contentType(MediaType.APPLICATION_JSON).content(payment))
        .andExpect(request().asyncStarted()).andReturn();
    String response = mvc.perform(asyncDispatch(tokenized))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    assertTrue(response.contains("paymentMethodId"));
    assertFalse(response.contains("4111111111111111"));
  }

  private Cookie csrfCookie() throws Exception {
    Cookie csrf = mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getCookie("XSRF-TOKEN");
    assertNotNull(csrf);
    assertFalse(csrf.isHttpOnly());
    return csrf;
  }

  private String basicHabuma() {
    String credentials = Base64.getEncoder().encodeToString(
        "habuma:password".getBytes(StandardCharsets.UTF_8));
    return "Basic " + credentials;
  }

  private User seedUser() {
    User habuma = Flux.interval(Duration.ZERO, Duration.ofMillis(100))
        .concatMap(tick -> userRepo.findByUsername("habuma"))
        .next().block(Duration.ofSeconds(10));
    assertNotNull(habuma);
    return habuma;
  }
}
