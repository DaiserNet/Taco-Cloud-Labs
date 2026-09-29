package tacos;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Document(collection = "tacoRatings")
@CompoundIndex(name = "rating_user_taco_unique",
    def = "{'userId': 1, 'tacoId': 1}", unique = true)
public class TacoRating {
  @Id
  private String id;
  private String userId;
  private String tacoId;
  private int score;
}
