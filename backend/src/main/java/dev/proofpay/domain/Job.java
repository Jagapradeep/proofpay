package dev.proofpay.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "jobs")
public class Job extends BaseEntity {

    private String title;
    private String description;
    private String clientEmail;
    private String freelancerEmail;
    private String currency;
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.DRAFT;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq")
    private List<Milestone> milestones = new ArrayList<>();

    protected Job() {}

    public Job(String title, String description, String clientEmail, String freelancerEmail, String currency) {
        this.title = title;
        this.description = description;
        this.clientEmail = clientEmail;
        this.freelancerEmail = freelancerEmail;
        this.currency = currency;
        this.totalAmount = BigDecimal.ZERO;
    }

    public Milestone addMilestone(String title, BigDecimal amount) {
        Milestone m = new Milestone(this, milestones.size() + 1, title, amount);
        milestones.add(m);
        totalAmount = totalAmount.add(amount);
        return m;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getClientEmail() { return clientEmail; }
    public String getFreelancerEmail() { return freelancerEmail; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public List<Milestone> getMilestones() { return milestones; }
}
