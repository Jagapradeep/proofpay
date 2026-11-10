package dev.proofpay.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One PayPal order + authorization per job: the client's money is held, not charged. */
@Entity
@Table(name = "payment_authorizations")
public class PaymentAuthorization extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    private Job job;

    private UUID orderRequestId = UUID.randomUUID();
    private String paypalOrderId;
    private String paypalAuthorizationId;
    private BigDecimal amount;
    private BigDecimal capturedAmount = BigDecimal.ZERO;
    private String currency;

    @Enumerated(EnumType.STRING)
    private AuthorizationStatus status = AuthorizationStatus.ORDER_CREATED;

    private Instant expiresAt;

    protected PaymentAuthorization() {}

    public PaymentAuthorization(Job job) {
        this.job = job;
        this.amount = job.getTotalAmount();
        this.currency = job.getCurrency();
    }

    public BigDecimal remaining() {
        return amount.subtract(capturedAmount);
    }

    public Job getJob() { return job; }
    public UUID getOrderRequestId() { return orderRequestId; }
    public String getPaypalOrderId() { return paypalOrderId; }
    public void setPaypalOrderId(String paypalOrderId) { this.paypalOrderId = paypalOrderId; }
    public String getPaypalAuthorizationId() { return paypalAuthorizationId; }
    public void setPaypalAuthorizationId(String id) { this.paypalAuthorizationId = id; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getCapturedAmount() { return capturedAmount; }
    public void setCapturedAmount(BigDecimal capturedAmount) { this.capturedAmount = capturedAmount; }
    public String getCurrency() { return currency; }
    public AuthorizationStatus getStatus() { return status; }
    public void setStatus(AuthorizationStatus status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
