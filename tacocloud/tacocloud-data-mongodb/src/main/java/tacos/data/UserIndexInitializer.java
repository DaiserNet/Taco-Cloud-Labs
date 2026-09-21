package tacos.data;

import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.User;

@Component
public class UserIndexInitializer {

  private final Mono<Void> uniqueIndexes;

  public UserIndexInitializer(ReactiveMongoTemplate mongoTemplate) {
    this.uniqueIndexes = Mono.defer(() -> mongoTemplate.indexOps(User.class)
        .ensureIndex(new Index().on("username", Direction.ASC)
            .named("username_unique").unique())
        .then(mongoTemplate.indexOps(User.class)
            .ensureIndex(new Index().on("email", Direction.ASC)
                .named("email_unique").unique()))
        .then())
        .cache();
  }

  public Mono<Void> ensureUniqueIndexes() {
    return uniqueIndexes;
  }
}
