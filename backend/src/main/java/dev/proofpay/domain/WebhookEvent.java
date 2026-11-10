package dev.proofpay.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    private String eventId;
    private String eventType;
    private Instant receivedAt = Instant.now();

    protected WebhookEvent() {}

    public WebhookEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
    }
}
