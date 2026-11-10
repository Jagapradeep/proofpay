package dev.proofpay.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "milestones")
public class Milestone extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Job job;

    private int seq;
    private String title;
    private BigDecimal amount;

    /** Testable acceptance criteria from the AI criteria compiler. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String criteriaJson;

    private String deliverableUrl;

    /** Latest verifier-agent report (scores, evidence, scanner findings, gate outcome). */
    @JdbcTypeCode(SqlTypes.JSON)
    private String verificationJson;

    @Enumerated(EnumType.STRING)
    private MilestoneStatus status = MilestoneStatus.PENDING;

    protected Milestone() {}

    Milestone(Job job, int seq, String title, BigDecimal amount) {
        this.job = job;
        this.seq = seq;
        this.title = title;
        this.amount = amount;
    }

    public Job getJob() { return job; }
    public int getSeq() { return seq; }
    public String getTitle() { return title; }
    public BigDecimal getAmount() { return amount; }
    public String getCriteriaJson() { return criteriaJson; }
    public void setCriteriaJson(String criteriaJson) { this.criteriaJson = criteriaJson; }
    public String getDeliverableUrl() { return deliverableUrl; }
    public void setDeliverableUrl(String deliverableUrl) { this.deliverableUrl = deliverableUrl; }
    public String getVerificationJson() { return verificationJson; }
    public void setVerificationJson(String verificationJson) { this.verificationJson = verificationJson; }
    public MilestoneStatus getStatus() { return status; }
    public void setStatus(MilestoneStatus status) { this.status = status; }
}
