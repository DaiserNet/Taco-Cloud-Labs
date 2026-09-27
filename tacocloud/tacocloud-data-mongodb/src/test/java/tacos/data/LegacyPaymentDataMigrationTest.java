package tacos.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.annotation.DirtiesContext;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;

@SpringBootTest(
    classes = LegacyPaymentDataMigrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.config.name=tc12-test",
        "spring.main.web-application-type=none",
        "spring.data.mongodb.port=0",
        "spring.data.mongodb.database=tc12-test",
        "spring.mongodb.embedded.version=3.5.5",
        "logging.level.org.springframework.boot.autoconfigure.mongo.embedded=OFF"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyPaymentDataMigrationTest {

  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
  })
  @Import(LegacyPaymentDataMigration.class)
  static class TestApplication {
  }

  @Autowired
  private ReactiveMongoTemplate mongo;

  @Autowired
  private LegacyPaymentDataMigration migration;

  @BeforeEach
  void cleanCollections() {
    StepVerifier.create(Mono.when(
        mongo.remove(new Query(), LegacyPaymentDataMigration.ORDER_COLLECTION),
        mongo.remove(new Query(),
            LegacyPaymentDataMigration.PAYMENT_METHOD_COLLECTION)))
        .verifyComplete();
  }

  @Test
  void shouldRemoveSensitiveFieldsWithoutDeletingSafeData() {
    Document order = new Document("_id", "LEGACY-ORDER")
        .append("deliveryName", "Synthetic Customer")
        .append("ccNumber", "4000000000000002")
        .append("ccExpiration", "12/99")
        .append("ccCVV", "123");
    Document payment = new Document("_id", "LEGACY-PAYMENT")
        .append("brand", "VISA")
        .append("last4", "0002")
        .append("ccNumber", "4000000000000002")
        .append("ccCVV", "123");

    Mono<LegacyPaymentDataMigration.MigrationReport> migrated =
        mongo.insert(order, LegacyPaymentDataMigration.ORDER_COLLECTION)
            .then(mongo.insert(payment,
                LegacyPaymentDataMigration.PAYMENT_METHOD_COLLECTION))
            .then(migration.removeSensitiveFields());

    StepVerifier.create(migrated.flatMap(report -> Mono.zip(
        mongo.findById("LEGACY-ORDER", Document.class,
            LegacyPaymentDataMigration.ORDER_COLLECTION),
        mongo.findById("LEGACY-PAYMENT", Document.class,
            LegacyPaymentDataMigration.PAYMENT_METHOD_COLLECTION),
        (storedOrder, storedPayment) ->
            new Object[] {report, storedOrder, storedPayment})))
        .assertNext(result -> {
          LegacyPaymentDataMigration.MigrationReport report =
              (LegacyPaymentDataMigration.MigrationReport) result[0];
          Document storedOrder = (Document) result[1];
          Document storedPayment = (Document) result[2];
          assertEquals(1, report.getOrdersModified());
          assertEquals(1, report.getPaymentMethodsModified());
          assertFalse(hasRawCardFields(storedOrder));
          assertFalse(hasRawCardFields(storedPayment));
          assertEquals("Synthetic Customer",
              storedOrder.getString("deliveryName"));
          assertEquals("VISA", storedPayment.getString("brand"));
        })
        .expectComplete()
        .verify(TEST_TIMEOUT);
  }

  @Test
  void shouldPersistNewPaymentAndOrderWithoutRawCardFields() {
    User user = new User("alice", "N/A", "Alice", "Street", "City", "ST",
        "00000", "0000000000", "alice@example.test");
    user.setId("USER-ID");
    PaymentMethod payment = new PaymentMethod(
        user, "tok_test", "VISA", "0002", "12/99");
    payment.setId("PAYMENT-ID");
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-ID");
    order.setUser(user);
    order.setPaymentMethodId("PAYMENT-ID");
    order.setPaymentBrand("VISA");
    order.setPaymentLast4("0002");

    Mono<Object[]> stored = mongo.save(payment)
        .then(mongo.save(order))
        .then(Mono.zip(
            mongo.findById("PAYMENT-ID", Document.class,
                LegacyPaymentDataMigration.PAYMENT_METHOD_COLLECTION),
            mongo.findById("ORDER-ID", Document.class,
                LegacyPaymentDataMigration.ORDER_COLLECTION),
            (storedPayment, storedOrder) ->
                new Object[] {storedPayment, storedOrder}));

    StepVerifier.create(stored)
        .assertNext(result -> {
          Document storedPayment = (Document) result[0];
          Document storedOrder = (Document) result[1];
          assertEquals("tok_test", storedPayment.getString("paymentToken"));
          assertEquals("0002", storedPayment.getString("last4"));
          assertEquals("PAYMENT-ID", storedOrder.getString("paymentMethodId"));
          assertFalse(hasRawCardFields(storedPayment));
          assertFalse(hasRawCardFields(storedOrder));
          assertFalse(storedOrder.toJson().contains("tok_test"));
        })
        .expectComplete()
        .verify(TEST_TIMEOUT);
  }

  private boolean hasRawCardFields(Document document) {
    return document.containsKey("ccNumber")
        || document.containsKey("ccExpiration")
        || document.containsKey("ccCVV");
  }
}
