package tacos.data;

import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import lombok.Value;
import reactor.core.publisher.Mono;

@Component
public class LegacyPaymentDataMigration {

  static final String ORDER_COLLECTION = "tacoOrder";
  static final String PAYMENT_METHOD_COLLECTION = "paymentMethod";

  private final ReactiveMongoTemplate mongo;

  public LegacyPaymentDataMigration(ReactiveMongoTemplate mongo) {
    this.mongo = mongo;
  }

  public Mono<MigrationReport> removeSensitiveFields() {
    Update removeRawCardData = new Update()
        .unset("ccNumber")
        .unset("ccExpiration")
        .unset("ccCVV");

    return Mono.zip(
        mongo.updateMulti(legacyFieldsQuery(), removeRawCardData, ORDER_COLLECTION),
        mongo.updateMulti(
            legacyFieldsQuery(), removeRawCardData, PAYMENT_METHOD_COLLECTION))
        .map(results -> new MigrationReport(
            results.getT1().getModifiedCount(),
            results.getT2().getModifiedCount()));
  }

  private Query legacyFieldsQuery() {
    return Query.query(new Criteria().orOperator(
        Criteria.where("ccNumber").exists(true),
        Criteria.where("ccExpiration").exists(true),
        Criteria.where("ccCVV").exists(true)));
  }

  @Value
  public static class MigrationReport {
    long ordersModified;
    long paymentMethodsModified;
  }
}
