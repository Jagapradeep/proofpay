package dev.proofpay.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
        /** A milestone may get a new release only if every earlier one failed at capture (no money moved). */
        boolean existsByMilestoneIdAndStatusNot(UUID milestoneId, ReleaseStatus status);

        /**
         * Row lock (SELECT ... FOR UPDATE): the API, a webhook and the poller can all try to advance the
         * same release at once; this makes them take turns, so one PayPal step never runs twice in parallel.
         */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select r from Release r where r.id = :id")
        Optional<Release> findForUpdate(@Param("id") UUID id);

        Optional<Release> findByPaypalCaptureId(String captureId);
        Optional<Release> findByPaypalPayoutBatchId(String batchId);
        List<Release> findByStatusIn(Collection<ReleaseStatus> statuses);
        List<Release> findAllByOrderByCreatedAtDesc();
    }

    public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
        List<AuditEvent> findByJobIdOrderByCreatedAtAsc(UUID jobId);
        List<AuditEvent> findAllByOrderByCreatedAtDesc();
        long countByMilestoneIdAndType(UUID milestoneId, String type);
    }

    public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {}
}
