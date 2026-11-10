package dev.proofpay.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All Spring Data repositories in one place (they're tiny). */
public final class Repositories {
    private Repositories() {}

    public interface JobRepository extends JpaRepository<Job, UUID> {}

    public interface MilestoneRepository extends JpaRepository<Milestone, UUID> {}

    public interface PaymentAuthorizationRepository extends JpaRepository<PaymentAuthorization, UUID> {
        Optional<PaymentAuthorization> findByJobId(UUID jobId);
        Optional<PaymentAuthorization> findByPaypalAuthorizationId(String paypalAuthorizationId);
    }

    public interface ReleaseRepository extends JpaRepository<Release, UUID> {
        Optional<Release> findByMilestoneId(UUID milestoneId);
        Optional<Release> findByPaypalCaptureId(String captureId);
        Optional<Release> findByPaypalPayoutBatchId(String batchId);
        List<Release> findByStatusIn(Collection<ReleaseStatus> statuses);
        List<Release> findAllByOrderByCreatedAtDesc();
    }

    public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
        List<AuditEvent> findByJobIdOrderByCreatedAtAsc(UUID jobId);
        List<AuditEvent> findAllByOrderByCreatedAtDesc();
    }

    public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {}
}
