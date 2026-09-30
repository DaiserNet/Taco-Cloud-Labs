package tacos.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.inventory.InventoryReservation;
import tacos.inventory.InventoryReservationStatus;
import tacos.web.api.OrderService;

@Service
public class OrderIdempotencyService {
  private final IdempotencyRecordRepository records;
  private final UserRepository users;
  private final OrderRepository orders;
  private final ReactiveMongoTemplate mongo;
  private final OrderService orderService;
  private final OrderMapper mapper;
  private final OrderRequestFingerprint fingerprint;
  private final Clock clock;
  private final int retentionHours;
  private final int waitMillis;

  public OrderIdempotencyService(IdempotencyRecordRepository records,
      UserRepository users, OrderRepository orders, ReactiveMongoTemplate mongo,
      OrderService orderService, OrderMapper mapper,
      OrderRequestFingerprint fingerprint, Clock clock,
      @Value("${tacocloud.order-idempotency.retention-hours:24}") int retentionHours,
      @Value("${tacocloud.order-idempotency.wait-millis:5000}") int waitMillis) {
    if (retentionHours < 1 || waitMillis < 1) {
      throw new IllegalArgumentException("Idempotency retention and wait must be positive.");
    }
    this.records = records;
    this.users = users;
    this.orders = orders;
    this.mongo = mongo;
    this.orderService = orderService;
    this.mapper = mapper;
    this.fingerprint = fingerprint;
    this.clock = clock;
    this.retentionHours = retentionHours;
    this.waitMillis = waitMillis;
  }

  public Mono<OrderResponse> create(OrderCreateRequest request, String key,
      Authentication authentication) {
    return Mono.defer(() -> {
      if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) {
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Idempotency-Key must contain 8 to 128 letters, digits, _ or -."));
      }
      if (authentication == null || !authentication.isAuthenticated()
          || authentication instanceof AnonymousAuthenticationToken) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      if (authentication.getAuthorities().stream().noneMatch(
          authority -> "ROLE_USER".equals(authority.getAuthority()))) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
      }
      String hash = fingerprint.hash(request);
      return users.findByUsername(authentication.getName())
          .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
          .flatMap(user -> {
            IdempotencyRecord claim = new IdempotencyRecord(
                UUID.randomUUID().toString(), user.getId(), key, hash,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                Instant.now(clock));
            return mongo.insert(claim)
                .map(inserted -> true)
                .onErrorResume(DuplicateKeyException.class, error -> Mono.just(false))
                .flatMap(owner -> owner
                    ? place(claim, request, authentication)
                    : replay(user.getId(), key, hash));
          });
    });
  }

  private Mono<OrderResponse> place(IdempotencyRecord claim,
      OrderCreateRequest request, Authentication authentication) {
    TacoOrder order = mapper.toEntity(request);
    order.setId(claim.getOrderId());
    order.setInventoryReservationId(claim.getReservationId());
    return orderService.createOrder(order, authentication,
            priced -> Mono.empty(), "HTTP_API", saved -> {
              claim.complete(mapper.toResponse(saved), Instant.now(clock), retentionHours);
              return records.save(claim).then();
            })
        .map(mapper::toResponse)
        .onErrorResume(error -> cleanupIfSafe(claim)
            .onErrorResume(cleanupError -> Mono.empty())
            .then(Mono.error(error)));
  }

  private Mono<Void> cleanupIfSafe(IdempotencyRecord claim) {
    return orders.existsById(claim.getOrderId())
        .flatMap(orderExists -> orderExists ? Mono.empty()
            : mongo.findById(claim.getReservationId(), InventoryReservation.class)
                .map(reservation -> reservation.getStatus()
                    == InventoryReservationStatus.RELEASED)
                .defaultIfEmpty(true)
                .flatMap(safe -> safe ? records.deleteById(claim.getId())
                    : Mono.empty()));
  }

  private Mono<OrderResponse> replay(String userId, String key, String hash) {
    return records.findByUserIdAndKey(userId, key)
        .switchIfEmpty(Mono.error(conflict("Idempotency claim is not ready.")))
        .flatMap(record -> {
          if (!hash.equals(record.getRequestHash())) {
            return Mono.error(conflict("Idempotency-Key belongs to a different request."));
          }
          if (record.getStatus() == IdempotencyRecord.Status.COMPLETED) {
            return Mono.just(record.getResponse());
          }
          long polls = Math.max(1L, (waitMillis + 49L) / 50L);
          return Flux.interval(Duration.ZERO, Duration.ofMillis(50))
              .take(polls)
              .concatMap(tick -> records.findById(record.getId()))
              .filter(current -> current.getStatus()
                  == IdempotencyRecord.Status.COMPLETED)
              .next()
              .map(IdempotencyRecord::getResponse)
              .switchIfEmpty(Mono.error(conflict(
                  "Order creation is still in progress. Retry with the same key.")));
        });
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
