package tacos.announcements;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "opsAnnouncements")
public class OpsAnnouncement {
  public enum Severity { INFO, WARNING, CRITICAL }

  @Id private String id;
  @Indexed(name = "announcement_active_slot_unique", unique = true,
      partialFilter = "{ 'active': true }")
  private int slot;
  private String text;
  private Severity severity;
  private Instant createdAt;
  private Instant expiresAt;
  private String createdBy;
  private boolean active;

  protected OpsAnnouncement() { }

  public OpsAnnouncement(String id, int slot, String text, Severity severity,
      Instant createdAt, Instant expiresAt, String createdBy) {
    this.id = id;
    this.slot = slot;
    this.text = text;
    this.severity = severity;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.createdBy = createdBy;
    this.active = true;
  }

  public String getId() { return id; }
  public int getSlot() { return slot; }
  public String getText() { return text; }
  public Severity getSeverity() { return severity; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getExpiresAt() { return expiresAt; }
  public String getCreatedBy() { return createdBy; }
  public boolean isActive() { return active; }
}
