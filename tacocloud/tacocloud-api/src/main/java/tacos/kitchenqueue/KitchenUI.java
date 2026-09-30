package tacos.kitchenqueue;

import javax.servlet.http.HttpServletRequest;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class KitchenUI {
  @GetMapping(path = {"/api/kitchen/ui", "/api/v1/kitchen/ui"},
      produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<Resource> display(HttpServletRequest request) {
    CsrfToken csrfToken = (CsrfToken) request.getAttribute(
        CsrfToken.class.getName());
    if (csrfToken != null) {
      csrfToken.getToken();
    }
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
        .body(new ClassPathResource("kitchen-ui.html"));
  }
}
