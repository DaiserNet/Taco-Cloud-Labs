package tacos.kitchen.delivery;

import java.time.Instant;

import javax.annotation.PostConstruct;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;

@Service
@Profile("rabbitmq-listener")
public class KitchenEventProcessor {
  public enum Result { PROCESSED, DUPLICATE }

  private final MongoTemplate mongo;
  private final TransactionTemplate transaction;

  public KitchenEventProcessor(MongoTemplate mongo,
      TransactionTemplate transaction) {
    this.mongo = mongo;
    this.transaction = transaction;
  }

  @PostConstruct
  void prepareCollections() {
    prepare(KitchenOrderReceipt.class);
    prepare(ProcessedOrderEvent.class);
  }

  private void prepare(Class<?> type) {
    if (mongo.collectionExists(type)) {
      return;
    }
    try {
      mongo.createCollection(type);
    } catch (RuntimeException error) {
      if (!mongo.collectionExists(type)) {
        throw error;
      }
    }
  }

  public Result process(OrderEvent event) {
    validate(event);
    try {
      return transaction.execute(status -> {
        if (mongo.exists(Query.query(Criteria.where("_id")
            .is(event.getEventId())), ProcessedOrderEvent.class)) {
          return Result.DUPLICATE;
        }
        String orderId = event.getPayload().getOrderId();
        boolean exists = mongo.exists(Query.query(Criteria.where("_id")
            .is(orderId)), KitchenOrderReceipt.class);
        Instant now = Instant.now();
        mongo.insert(new ProcessedOrderEvent(event.getEventId(), orderId,
            exists ? "ALREADY_RECEIVED" : "RECEIVED", now));
        if (!exists) {
          mongo.insert(new KitchenOrderReceipt(orderId, event.getEventId(), now));
        }
        return Result.PROCESSED;
      });
    } catch (DuplicateKeyException conflict) {
      if (mongo.exists(Query.query(Criteria.where("_id")
          .is(event.getEventId())), ProcessedOrderEvent.class)) {
        return Result.DUPLICATE;
      }
      throw new TransientDataAccessResourceException(
          "Concurrent kitchen receipt", conflict);
    }
  }

  private void validate(OrderEvent event) {
    if (event == null) {
      throw new PermanentOrderEventException("INVALID_EVENT");
    }
    if (event.getVersion() != OrderEvent.CURRENT_VERSION) {
      throw new PermanentOrderEventException("UNSUPPORTED_VERSION");
    }
    if (event.getEventType() != OrderEventType.ORDER_CREATED
        || event.getPayload() == null
        || !StringUtils.hasText(event.getPayload().getOrderId())) {
      throw new PermanentOrderEventException("INVALID_EVENT");
    }
  }
}
