package tacos.reorder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validator;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderLine;
import tacos.ReorderAttempt;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.ReorderConfirmResponse;
import tacos.api.dto.ReorderQuoteResponse;
import tacos.api.dto.ReorderQuoteResponse.IngredientDifference;
import tacos.api.dto.ReorderRequest;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.history.OrderHistoryService;
import tacos.inventory.InsufficientStockException;
import tacos.payment.PaymentMethodService;
import tacos.pricing.CouponService;
import tacos.pricing.OrderPricingService;
import tacos.web.api.OrderService;

@Service
public class ReorderService {
  private final OrderHistoryService history;
  private final OrderMapper mapper;
  private final Validator validator;
  private final PaymentMethodService paymentMethods;
  private final OrderPricingService pricing;
  private final CouponService coupons;
  private final OrderService orders;
  private final OrderRepository orderRepo;
  private final ReactiveMongoTemplate mongo;

  public ReorderService(OrderHistoryService history, OrderMapper mapper,
      Validator validator, PaymentMethodService paymentMethods,
      OrderPricingService pricing, CouponService coupons,
      OrderService orders, OrderRepository orderRepo,
      ReactiveMongoTemplate mongo) {
    this.history = history;
    this.mapper = mapper;
    this.validator = validator;
    this.paymentMethods = paymentMethods;
    this.pricing = pricing;
    this.coupons = coupons;
    this.orders = orders;
    this.orderRepo = orderRepo;
    this.mongo = mongo;
  }

  public Mono<ReorderQuoteResponse> quote(String sourceOrderId,
      ReorderRequest request, Authentication authentication) {
    return history.findOwnedOrder(sourceOrderId, authentication)
        .flatMap(source -> prepare(source, request, authentication))
        .map(prepared -> prepared.quote);
  }

  public Mono<ReorderConfirmResponse> confirm(String sourceOrderId,
      ReorderRequest request, String idempotencyKey,
      Authentication authentication) {
    return Mono.defer(() -> {
      if (!StringUtils.hasText(request.getQuoteFingerprint())
          || !idempotencyKeyValid(idempotencyKey)) {
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Quote fingerprint and Idempotency-Key are required."));
      }
      return history.findOwnedOrder(sourceOrderId, authentication)
          .flatMap(source -> {
            String userId = source.getUser().getId();
            String attemptId = hash(userId, idempotencyKey);
            return mongo.findById(attemptId, ReorderAttempt.class)
                .flatMap(existing -> replay(existing, sourceOrderId,
                    request))
                .switchIfEmpty(Mono.defer(() ->
                    prepare(source, request, authentication)
                        .flatMap(prepared -> {
                          if (!prepared.quote.getQuoteFingerprint()
                              .equals(request.getQuoteFingerprint())) {
                            return Mono.error(staleQuote());
                          }
                          return claimAndCreate(attemptId, source,
                              prepared, request, authentication);
                        })));
          });
    });
  }

  private Mono<Prepared> prepare(TacoOrder source, ReorderRequest request,
      Authentication authentication) {
    return paymentMethods.findOwned(request.getPaymentMethodId(), authentication)
        .then(Mono.defer(() -> {
          OrderCreateRequest createRequest = mapper.toReorderRequest(source,
              request.getPaymentMethodId(), request.getCouponCode());
          java.util.Set<ConstraintViolation<OrderCreateRequest>> violations =
              validator.validate(createRequest);
          if (!violations.isEmpty()) {
            return Mono.error(new ConstraintViolationException(violations));
          }
          TacoOrder fresh = mapper.toEntity(createRequest);
          return pricing.price(fresh).flatMap(coupons::apply)
              .map(priced -> {
                checkStock(priced);
                List<IngredientDifference> differences =
                    ingredientDifferences(source, priced);
                String fingerprint = fingerprint(source, priced,
                    request.getPaymentMethodId());
                ReorderQuoteResponse quote = new ReorderQuoteResponse(
                    source.getId(), priced.getCurrency(),
                    money(source.getSubtotal()), money(priced.getSubtotal()),
                    money(source.getTotal()), money(priced.getTotal()),
                    money(priced.getTotal()).subtract(money(source.getTotal())),
                    priced.isCouponApplied(), differences, fingerprint, true);
                return new Prepared(createRequest, quote);
              });
        }));
  }

  private Mono<ReorderConfirmResponse> claimAndCreate(String attemptId,
      TacoOrder source, Prepared prepared, ReorderRequest request,
      Authentication authentication) {
    ReorderAttempt attempt = new ReorderAttempt(attemptId,
        source.getUser().getId(), source.getId(),
        prepared.quote.getQuoteFingerprint(), requestFingerprint(request),
        freshId(source.getId()));
    return mongo.insert(attempt).map(Optional::of)
        .onErrorResume(DuplicateKeyException.class,
            error -> Mono.just(Optional.empty()))
        .flatMap(claimed -> claimed.isPresent()
            ? createClaimedOrder(source, prepared, request, authentication,
                attempt)
            : mongo.findById(attemptId, ReorderAttempt.class)
                .switchIfEmpty(Mono.error(conflict()))
                .flatMap(existing -> replay(existing, source.getId(),
                    request)));
  }

  private Mono<ReorderConfirmResponse> createClaimedOrder(TacoOrder source,
      Prepared prepared, ReorderRequest request,
      Authentication authentication, ReorderAttempt attempt) {
    TacoOrder fresh = mapper.toEntity(prepared.createRequest);
    fresh.setId(attempt.getNewOrderId());
    return orders.createOrder(fresh, authentication, priced -> Mono.defer(() -> {
      checkStock(priced);
      return fingerprint(source, priced, request.getPaymentMethodId())
          .equals(attempt.getQuoteFingerprint())
          ? Mono.empty() : Mono.error(staleQuote());
    }), "REORDER")
        .flatMap(saved -> mongo.updateFirst(
                Query.query(Criteria.where("_id").is(attempt.getId())
                    .and("completed").is(false)),
                Update.update("completed", true), ReorderAttempt.class)
            .flatMap(result -> result.getModifiedCount() == 1
                ? Mono.just(new ReorderConfirmResponse(
                    mapper.toResponse(saved), false))
                : Mono.error(conflict())))
        .onErrorResume(error -> releaseClaimIfNoOrder(attempt)
            .then(Mono.error(error)));
  }

  private Mono<Void> releaseClaimIfNoOrder(ReorderAttempt attempt) {
    return orderRepo.existsById(attempt.getNewOrderId())
        .flatMap(exists -> exists ? Mono.empty()
            : mongo.remove(Query.query(Criteria.where("_id")
                .is(attempt.getId()).and("completed").is(false)),
                ReorderAttempt.class).then());
  }

  private Mono<ReorderConfirmResponse> replay(ReorderAttempt attempt,
      String sourceOrderId, ReorderRequest request) {
    if (!sourceOrderId.equals(attempt.getSourceOrderId())
        || !request.getQuoteFingerprint().equals(attempt.getQuoteFingerprint())
        || !requestFingerprint(request).equals(attempt.getRequestFingerprint())
        || !attempt.isCompleted()) {
      return Mono.error(conflict());
    }
    return orderRepo.findById(attempt.getNewOrderId())
        .switchIfEmpty(Mono.error(conflict()))
        .map(order -> new ReorderConfirmResponse(mapper.toResponse(order), true));
  }

  private void checkStock(TacoOrder order) {
    Map<String, Integer> needed = new LinkedHashMap<>();
    Map<String, Ingredient> catalog = new LinkedHashMap<>();
    for (OrderLine line : order.getItems()) {
      for (Ingredient ingredient : line.getTaco().getIngredients()) {
        needed.merge(ingredient.getId(), line.getQuantity(), Math::addExact);
        catalog.put(ingredient.getId(), ingredient);
      }
    }
    needed.forEach((id, quantity) -> {
      if (catalog.get(id).getStockOnHand() < quantity) {
        throw new InsufficientStockException(id, quantity);
      }
    });
  }

  private List<IngredientDifference> ingredientDifferences(
      TacoOrder source, TacoOrder priced) {
    Map<String, IngredientDifference> differences = new LinkedHashMap<>();
    List<OrderLine> previous = sourceLines(source);
    for (int i = 0; i < priced.getItems().size(); i++) {
      Map<String, Ingredient> oldById = new LinkedHashMap<>();
      Taco oldTaco = previous.get(i).getTaco();
      for (Ingredient old : safe(oldTaco.getIngredients())) {
        if (old != null) {
          oldById.put(old.getId(), old);
        }
      }
      for (Ingredient current : priced.getItems().get(i).getTaco()
          .getIngredients()) {
        Ingredient old = oldById.get(current.getId());
        if (old == null) {
          continue;
        }
        boolean metadataChanged = !Objects.equals(old.getName(), current.getName())
            || old.getType() != current.getType()
            || !Objects.equals(old.getDietaryTags(), current.getDietaryTags())
            || !Objects.equals(old.getAllergens(), current.getAllergens())
            || old.getSpiceLevel() != current.getSpiceLevel();
        if (metadataChanged || !Objects.equals(
            old.getUnitPrice(), current.getUnitPrice())) {
          differences.put(current.getId(), new IngredientDifference(
              current.getId(), old.getName(), current.getName(),
              old.getUnitPrice(), current.getUnitPrice(), metadataChanged));
        }
      }
    }
    return new ArrayList<>(differences.values());
  }

  private List<OrderLine> sourceLines(TacoOrder source) {
    if (source.getItems() != null && !source.getItems().isEmpty()) {
      return source.getItems();
    }
    List<OrderLine> lines = new ArrayList<>();
    for (Taco taco : safe(source.getTacos())) {
      OrderLine line = new OrderLine();
      line.setTaco(taco);
      line.setQuantity(1);
      lines.add(line);
    }
    return lines;
  }

  private String fingerprint(TacoOrder source, TacoOrder priced,
      String paymentMethodId) {
    List<String> parts = new ArrayList<>();
    Collections.addAll(parts, source.getId(), source.getDeliveryName(),
        source.getDeliveryStreet(), source.getDeliveryCity(),
        source.getDeliveryState(), source.getDeliveryZip(),
        money(source.getSubtotal()).toPlainString(),
        money(source.getTotal()).toPlainString(), paymentMethodId,
        priced.getCouponCode(), priced.getCurrency(),
        money(priced.getSubtotal()).toPlainString(),
        money(priced.getDiscount()).toPlainString(),
        money(priced.getTotal()).toPlainString());
    for (OrderLine line : priced.getItems()) {
      Collections.addAll(parts, line.getTaco().getName(),
          String.valueOf(line.getQuantity()),
          money(line.getUnitPriceAtPurchase()).toPlainString());
      for (Ingredient ingredient : line.getTaco().getIngredients()) {
        Collections.addAll(parts, ingredient.getId(), ingredient.getName(),
            String.valueOf(ingredient.getType()),
            money(ingredient.getUnitPrice()).toPlainString(),
            String.valueOf(ingredient.getSpiceLevel()),
            new TreeSet<>(ingredient.getDietaryTags()).toString(),
            new TreeSet<>(ingredient.getAllergens()).toString());
      }
    }
    return hash(parts.toArray(new String[0]));
  }

  private String hash(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
      }
      byte[] result = digest.digest();
      StringBuilder hex = new StringBuilder(result.length * 2);
      for (byte value : result) {
        hex.append(Character.forDigit((value >>> 4) & 15, 16));
        hex.append(Character.forDigit(value & 15, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable.", error);
    }
  }

  private String freshId(String sourceOrderId) {
    String id;
    do {
      id = UUID.randomUUID().toString();
    } while (id.equals(sourceOrderId));
    return id;
  }

  private String requestFingerprint(ReorderRequest request) {
    String coupon = request.getCouponCode() == null ? ""
        : request.getCouponCode().trim().toUpperCase(java.util.Locale.ROOT);
    return hash(request.getPaymentMethodId(), coupon);
  }

  private boolean idempotencyKeyValid(String key) {
    return key != null && key.matches("[A-Za-z0-9_-]{8,128}");
  }

  private BigDecimal money(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value)
        .setScale(2, RoundingMode.HALF_UP);
  }

  private ResponseStatusException staleQuote() {
    return new ResponseStatusException(HttpStatus.CONFLICT,
        "Quote changed. Request a new quote before confirming.");
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT,
        "Idempotency key is in use or has a different confirmation.");
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? Collections.emptyList() : values;
  }

  private static class Prepared {
    private final OrderCreateRequest createRequest;
    private final ReorderQuoteResponse quote;

    private Prepared(OrderCreateRequest createRequest,
        ReorderQuoteResponse quote) {
      this.createRequest = createRequest;
      this.quote = quote;
    }
  }
}
