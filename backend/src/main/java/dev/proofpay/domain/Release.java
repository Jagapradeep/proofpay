package dev.proofpay.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Paying one milestone: a partial capture on the job's authorization, then a payout.
 * Request ids are created once with the row and reused ONLY for retries of this release.
 * Never derive them from job/milestone numbers: PayPal replays the cached response for a
 * reused PayPal-Request-Id, even against a different authorization.
 */
@Entity
@Table(name = "releases")
public class Release extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Job job;

    /** At most one release per milestone that is not CAPTURE_FAILED (partial unique index, V4). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Milestone milestone;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private PaymentAuthorization authorization;

    private BigDecimal amount;
    private String currency;
    private String payeeEmail;
    private boolean finalCapture;

    @Enumerated(EnumType.STRING)
    private ReleaseStatus status = ReleaseStatus.VERIFIED;

    private UUID captureRequestId = UUID.randomUUID();
    private UUID payoutRequestId = UUID.randomUUID();
    private String paypalCaptureId;
    private String paypalPayoutBatchId;
    private String paypalPayoutItemId;
    private String failureReason;

    @Version
    private long version;

    protected Release() {}

    public Release(Milestone milestone, PaymentAuthorization authorization, BigDecimal amount, boolean finalCapture) {
        this.job = milestone.getJob();
        this.milestone = milestone;
        this.authorization = authorization;
        this.amount = amount;
        this.currency = authorization.getCurrency();
        this.payeeEmail = job.getFreelancerEmail();
        this.finalCapture = finalCapture;
    }

    /** All status changes go through here so illegal jumps fail loudly. */
    public void moveTo(ReleaseStatus target) {
        if (!status.canMoveTo(target)) {
            throw new IllegalStateException("Release " + getId() + ": " + status + " -> " + target + " not allowed");
        }
        status = target;
    }

    /** A failed payout batch can't be resent with the same sender_batch_id, so a retry is a new operation. */
    public void newPayoutAttempt() {
        payoutRequestId = UUID.randomUUID();
        paypalPayoutBatchId = null;
        paypalPayoutItemId = null;
        failureReason = null;
    }

    public Job getJob() { return job; }
    public Milestone getMilestone() { return milestone; }
    public PaymentAuthorization getAuthorization() { return authorization; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPayeeEmail() { return payeeEmail; }
    public boolean isFinalCapture() { return finalCapture; }
    public ReleaseStatus getStatus() { return status; }
    public UUID getCaptureRequestId() { return captureRequestId; }
    public UUID getPayoutRequestId() { return payoutRequestId; }
    public String getPaypalCaptureId() { return paypalCaptureId; }
    public void setPaypalCaptureId(String id) { this.paypalCaptureId = id; }
    public String getPaypalPayoutBatchId() { return paypalPayoutBatchId; }
    public void setPaypalPayoutBatchId(String id) { this.paypalPayoutBatchId = id; }
    public String getPaypalPayoutItemId() { return paypalPayoutItemId; }
    public void setPaypalPayoutItemId(String id) { this.paypalPayoutItemId = id; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
