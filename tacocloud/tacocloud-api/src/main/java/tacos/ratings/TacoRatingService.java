package tacos.ratings;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.limit;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.lookup;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.sort;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.unwind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoRating;
import tacos.User;
import tacos.api.dto.TacoRankingResponse;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@Service
public class TacoRatingService {
  private final ReactiveMongoTemplate mongo;
  private final TacoRepository tacoRepo;
  private final UserRepository userRepo;
  private final int minVotes;
  private final int maxTopLimit;

  public TacoRatingService(ReactiveMongoTemplate mongo, TacoRepository tacoRepo,
      UserRepository userRepo,
      @Value("${tacocloud.ratings.min-votes:2}") int minVotes,
      @Value("${tacocloud.ratings.max-top-limit:50}") int maxTopLimit) {
    if (minVotes < 1 || maxTopLimit < 1) {
      throw new IllegalArgumentException("Rating limits must be positive.");
    }
    this.mongo = mongo;
    this.tacoRepo = tacoRepo;
    this.userRepo = userRepo;
    this.minVotes = minVotes;
    this.maxTopLimit = maxTopLimit;
  }

  public Mono<Void> rate(String tacoId, int score,
      Authentication authentication) {
    return Mono.defer(() -> {
      if (score < 1 || score > 5) {
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "score must be between 1 and 5."));
      }
      return currentUser(authentication)
          .flatMap(user -> tacoRepo.findById(tacoId)
              .filter(Taco::isPublished)
              .switchIfEmpty(Mono.error(new ResponseStatusException(
                  HttpStatus.NOT_FOUND, "El taco no existe o no está publicado.")))
              .flatMap(taco -> upsert(user.getId(), tacoId, score)))
          .then();
    });
  }

  public Flux<TacoRankingResponse> top(int limit) {
    return Flux.defer(() -> {
      if (limit < 1 || limit > maxTopLimit) {
        return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "limit fuera de rango."));
      }
      Aggregation ranking = newAggregation(
          lookup(mongo.getCollectionName(Taco.class), "tacoId", "_id", "taco"),
          unwind("taco"),
          match(Criteria.where("taco.published").ne(false)),
          group("tacoId")
              .first("taco.name").as("tacoName")
              .count().as("voteCount")
              .avg("score").as("average")
              .sum(stars(1)).as("stars1")
              .sum(stars(2)).as("stars2")
              .sum(stars(3)).as("stars3")
              .sum(stars(4)).as("stars4")
              .sum(stars(5)).as("stars5"),
          match(Criteria.where("voteCount").gte(minVotes)),
          sort(Sort.by(Sort.Order.desc("average"),
              Sort.Order.desc("voteCount"), Sort.Order.asc("_id"))),
          limit(limit));
      return mongo.aggregate(ranking, TacoRating.class, Document.class)
          .map(this::toResponse);
    });
  }

  private ConditionalOperators.Cond stars(int score) {
    return ConditionalOperators.when(Criteria.where("score").is(score))
        .then(1).otherwise(0);
  }

  private TacoRankingResponse toResponse(Document row) {
    BigDecimal average = BigDecimal.valueOf(
        ((Number) row.get("average")).doubleValue())
        .setScale(2, RoundingMode.HALF_UP);
    return new TacoRankingResponse(row.getString("_id"),
        row.getString("tacoName"), average,
        ((Number) row.get("voteCount")).longValue(),
        Arrays.asList(count(row, "stars1"), count(row, "stars2"),
            count(row, "stars3"), count(row, "stars4"),
            count(row, "stars5")));
  }

  private long count(Document row, String field) {
    return ((Number) row.get(field)).longValue();
  }

  private Mono<Void> upsert(String userId, String tacoId, int score) {
    Query query = Query.query(Criteria.where("userId").is(userId)
        .and("tacoId").is(tacoId));
    Update update = Update.update("score", score);
    return mongo.upsert(query, update, TacoRating.class)
        .onErrorResume(DuplicateKeyException.class,
            error -> mongo.updateFirst(query, update, TacoRating.class))
        .then();
  }

  private Mono<User> currentUser(Authentication authentication) {
    return Mono.defer(() -> {
      if (authentication == null || !authentication.isAuthenticated()
          || authentication instanceof AnonymousAuthenticationToken) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
      }
      boolean userRole = authentication.getAuthorities().stream()
          .anyMatch(authority -> "ROLE_USER".equals(authority.getAuthority()));
      if (!userRole) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
      }
      return userRepo.findByUsername(authentication.getName())
          .filter(user -> StringUtils.hasText(user.getId()))
          .switchIfEmpty(Mono.error(
              new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    });
  }
}
