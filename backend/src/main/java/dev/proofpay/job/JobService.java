package dev.proofpay.job;

import dev.proofpay.audit.AuditService;
import dev.proofpay.domain.*;
import dev.proofpay.domain.Repositories.JobRepository;
import dev.proofpay.domain.Repositories.MilestoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobs;
    private final MilestoneRepository milestones;
    private final AuditService audit;

    public JobService(JobRepository jobs, MilestoneRepository milestones, AuditService audit) {
        this.jobs = jobs;
        this.milestones = milestones;
        this.audit = audit;
    }

    public record NewMilestone(String title, BigDecimal amount) {}

    @Transactional
    public Job create(String title, String description, String clientEmail, String freelancerEmail,
                      String currency, List<NewMilestone> ms) {
        if (clientEmail.equalsIgnoreCase(freelancerEmail)) {
            throw new IllegalArgumentException("Client and freelancer must be different accounts");
        }
        Job job = new Job(title, description, clientEmail, freelancerEmail, currency == null ? "USD" : currency);
        ms.forEach(m -> job.addMilestone(m.title(), m.amount().setScale(2)));
        jobs.save(job);
        audit.record(job.getId(), null, null, "JOB_CREATED", AuditService.CLIENT,
                Map.of("total", job.getTotalAmount(), "milestones", ms.size()));
        return job;
    }

    /** Freelancer hands in a deliverable (MVP: a GitHub repo URL). */
    @Transactional
    public Milestone submit(UUID milestoneId, String deliverableUrl) {
        Milestone m = milestone(milestoneId);
        if (m.getJob().getStatus() != JobStatus.FUNDED) {
            throw new IllegalStateException("Job is " + m.getJob().getStatus() + "; it must be FUNDED before submitting");
        }
        if (!Set.of(MilestoneStatus.PENDING, MilestoneStatus.NEEDS_REVIEW).contains(m.getStatus())) {
            throw new IllegalStateException("Milestone is " + m.getStatus() + ", cannot submit");
        }
        m.setDeliverableUrl(deliverableUrl);
        m.setStatus(MilestoneStatus.SUBMITTED);
        audit.record(m.getJob().getId(), m.getId(), null, "DELIVERABLE_SUBMITTED", AuditService.FREELANCER,
                Map.of("url", deliverableUrl));
        return m;
    }

    @Transactional(readOnly = true)
    public Job get(UUID id) {
        Job job = jobs.findById(id).orElseThrow(() -> new NoSuchElementException("Job not found: " + id));
        job.getMilestones().size(); // load while the session is open
        return job;
    }

    private Milestone milestone(UUID id) {
        return milestones.findById(id).orElseThrow(() -> new NoSuchElementException("Milestone not found: " + id));
    }
}
