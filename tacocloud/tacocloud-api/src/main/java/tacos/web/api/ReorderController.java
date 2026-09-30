package tacos.web.api;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.ReorderConfirmResponse;
import tacos.api.dto.ReorderQuoteResponse;
import tacos.api.dto.ReorderRequest;
import tacos.correlation.CorrelationContext;
import tacos.reorder.ReorderService;

@RestController
@RequestMapping(path = "/api/orders/me/{sourceOrderId}/reorder",
    produces = "application/json")
public class ReorderController {
  private final ReorderService reorders;

  public ReorderController(ReorderService reorders) {
    this.reorders = reorders;
  }

  @PostMapping(path = "/quote", consumes = "application/json")
  public Mono<ReorderQuoteResponse> quote(
      @PathVariable String sourceOrderId,
      @Valid @RequestBody ReorderRequest request,
      Authentication authentication) {
    return reorders.quote(sourceOrderId, request, authentication);
  }

  @PostMapping(consumes = "application/json")
  public Mono<ResponseEntity<ReorderConfirmResponse>> confirm(
      @PathVariable String sourceOrderId,
      @Valid @RequestBody ReorderRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication) {
    return CorrelationContext.fromCurrentRequest(
        reorders.confirm(sourceOrderId, request, idempotencyKey,
            authentication)
            .map(result -> ResponseEntity.status(result.isReplayed()
                ? HttpStatus.OK : HttpStatus.CREATED).body(result)));
  }
}
