package dev.proofpay.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID jobId;
    private UUID milestoneId;
    private UUID releaseId;
    private String type;
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    private Instant createdAt = Instant.now();

    protected AuditEvent() {}

    public AuditEvent(UUID jobId, UUID milestoneId, UUID releaseId, String type, String actor, String detail) {
        this.jobId = jobId;
        this.milestoneId = milestoneId;
        this.releaseId = releaseId;
        this.type = type;
        this.actor = actor;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public UUID getJobId() { return jobId; }
    public UUID getMilestoneId() { return milestoneId; }
    public UUID getReleaseId() { return releaseId; }
    public String getType() { return type; }
    public String getActor() { return actor; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
