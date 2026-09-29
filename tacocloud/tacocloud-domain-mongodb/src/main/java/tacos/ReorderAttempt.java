package tacos;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Document(collection = "reorderAttempts")
public class ReorderAttempt {
  @Id
  private String id;
  private String userId;
  private String sourceOrderId;
  private String quoteFingerprint;
  private String requestFingerprint;
  private String newOrderId;
  private boolean completed;

  public ReorderAttempt(String id, String userId, String sourceOrderId,
      String quoteFingerprint, String requestFingerprint, String newOrderId) {
    this.id = id;
    this.userId = userId;
    this.sourceOrderId = sourceOrderId;
    this.quoteFingerprint = quoteFingerprint;
    this.requestFingerprint = requestFingerprint;
    this.newOrderId = newOrderId;
  }
}
