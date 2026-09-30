package tacos.announcements;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/admin/announcements", produces = "application/json")
public class AnnouncementController {
  private final AnnouncementService announcements;

  public AnnouncementController(AnnouncementService announcements) {
    this.announcements = announcements;
  }

  @GetMapping
  public Flux<AnnouncementResponse> list(Authentication authentication) {
    return announcements.list(authentication);
  }

  @GetMapping("/{id}")
  public Mono<AnnouncementResponse> get(@PathVariable String id,
      Authentication authentication) {
    return announcements.get(id, authentication);
  }

  @PostMapping(consumes = "application/json")
  public Mono<ResponseEntity<AnnouncementResponse>> create(
      @RequestBody AnnouncementRequest request,
      Authentication authentication) {
    return announcements.create(request, authentication)
        .map(response -> ResponseEntity.status(HttpStatus.CREATED)
            .body(response));
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> delete(@PathVariable String id,
      Authentication authentication) {
    return announcements.delete(id, authentication)
        .thenReturn(ResponseEntity.noContent().build());
  }
}
