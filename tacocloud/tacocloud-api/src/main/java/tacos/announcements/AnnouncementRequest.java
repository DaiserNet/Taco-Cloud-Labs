package tacos.announcements;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public class AnnouncementRequest {
  private String text;
  private OpsAnnouncement.Severity severity;
  private Instant expiresAt;

  public String getText() { return text; }
  public void setText(String text) { this.text = text; }
  public OpsAnnouncement.Severity getSeverity() { return severity; }
  public void setSeverity(OpsAnnouncement.Severity severity) {
    this.severity = severity;
  }
  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Field not allowed in announcement: " + name);
  }
}
