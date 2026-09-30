package tacos.announcements;

import java.time.Instant;

public class AnnouncementResponse {
  private final String id;
  private final String text;
  private final OpsAnnouncement.Severity severity;
  private final Instant createdAt;
  private final Instant expiresAt;
  private final boolean active;

  public AnnouncementResponse(OpsAnnouncement announcement) {
    this.id = announcement.getId();
    this.text = announcement.getText();
    this.severity = announcement.getSeverity();
    this.createdAt = announcement.getCreatedAt();
    this.expiresAt = announcement.getExpiresAt();
    this.active = announcement.isActive();
  }

  public String getId() { return id; }
  public String getText() { return text; }
  public OpsAnnouncement.Severity getSeverity() { return severity; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getExpiresAt() { return expiresAt; }
  public boolean isActive() { return active; }
}
