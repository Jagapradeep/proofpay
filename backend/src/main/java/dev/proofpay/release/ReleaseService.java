package dev.proofpay.release;

import dev.proofpay.audit.AuditService;
import dev.proofpay.domain.*;
import dev.proofpay.domain.Repositories.*;
import dev.proofpay.paypal.PayPalApiException;
import dev.proofpay.paypal.PayPalClient;
import dev.proofpay.paypal.PayPalDtos.*;
import dev.proofpay.paypal.PayPalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Drives a release through VERIFIED -> CAPTURED -> PAYOUT_PENDING -> PAID | PAYOUT_FAILED.
 *
 * {@link #advance} runs one step at a time, each in its own transaction, and is safe to call
 * from the API, a webhook or the poller: every step re-reads PayPal state and reuses the
 * release's persisted request ids, so repeats never move money twice.
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    /** States where something still has to happen. */
    public static final Set<ReleaseStatus> IN_FLIGHT = Set.of(
            ReleaseStatus.VERIFIED, ReleaseStatus.CAPTURE_PENDING, ReleaseStatus.CAPTURED, ReleaseStatus.PAYOUT_PENDING);

    private static final Set<String> PAYOUT_ITEM_FAILED =
            Set.of("FAILED", "RETURNED", "BLOCKED", "REFUNDED", "REVERSED", "DENIED");

    private final ReleaseRepository releases;
    private final MilestoneRepository milestones;
    private final PaymentAuthorizationRepository authorizations;
    private final JobRepository jobs;
    private final PolicyGate gate;
    private final PayPalClient paypal;
    private final PayPalProperties props;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public ReleaseService(ReleaseRepository releases, MilestoneRepository milestones,
                          PaymentAuthorizationRepository authorizations, JobRepository jobs,
                          PolicyGate gate, PayPalClient paypal, PayPalProperties props,
                          AuditService audit, TransactionTemplate tx) {
        this.releases = releases;
        this.milestones = milestones;
        this.authorizations = authorizations;
        this.jobs = jobs;
        this.gate = gate;
        this.paypal = paypal;
        this.props = props;
        this.audit = audit;
        this.tx = tx;
    }

    public record ProposalResult(PolicyGate.Decision decision, UUID releaseId) {}

    /** Entry point for the verifier agent's proposeRelease tool (and the human-review path). */
    public ProposalResult propose(ReleaseProposal p, String actor) {
        ProposalResult result = tx.execute(s -> {
            Milestone m = milestones.findById(p.milestoneId())
                    .orElseThrow(() -> new NoSuchElementException("Milestone not found: " + p.milestoneId()));
            Job job = m.getJob();
            PaymentAuthorization auth = authorizations.findByJobId(job.getId()).orElse(null);
            boolean exists = releases.findByMilestoneId(m.getId()).isPresent();

            PolicyGate.Decision d = gate.evaluate(p, m, auth, exists);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("amount", p.amount());
            detail.put("confidence", p.confidence());
            detail.put("injectionFlagged", p.injectionFlagged());
            detail.put("evidence", p.evidence());
            detail.put("reasons", d.reasons());
            audit.record(job.getId(), m.getId(), null, "RELEASE_PROPOSED", actor, detail);

            if (!d.approved()) {
                if (m.getStatus() == MilestoneStatus.SUBMITTED) m.setStatus(MilestoneStatus.NEEDS_REVIEW);
                audit.record(job.getId(), m.getId(), null, "GATE_BLOCKED", AuditService.GATE, Map.of("reasons", d.reasons()));
                return new ProposalResult(d, null);
            }

            boolean finalCapture = p.amount().compareTo(auth.remaining()) == 0;
            Release r = releases.save(new Release(m, auth, p.amount(), finalCapture));
            m.setStatus(MilestoneStatus.RELEASING);
            audit.record(job.getId(), m.getId(), r.getId(), "GATE_APPROVED", AuditService.GATE,
                    Map.of("amount", p.amount(), "finalCapture", finalCapture));
            return new ProposalResult(d, r.getId());
        });
        if (result.releaseId() != null) advance(result.releaseId());
        return result;
    }

    /** Runs steps until the release is waiting on PayPal or done. */
    public void advance(UUID releaseId) {
        for (int i = 0; i < 4; i++) {
            Boolean progressed = tx.execute(s -> step(releaseId));
            if (!Boolean.TRUE.equals(progressed)) return;
        }
    }

    /** Explicit retry after PAYOUT_FAILED (e.g. once the payee fixed their PayPal account). */
    public void retryPayout(UUID releaseId) {
        tx.executeWithoutResult(s -> {
            Release r = load(releaseId);
            r.moveTo(ReleaseStatus.CAPTURED);
            r.newPayoutAttempt();
            audit.record(r.getJob().getId(), r.getMilestone().getId(), r.getId(), "PAYOUT_RETRY", AuditService.SYSTEM, null);
        });
        advance(releaseId);
    }

    public void onCaptureEvent(String captureId) {
        releases.findByPaypalCaptureId(captureId).ifPresent(r -> advance(r.getId()));
    }

    public void onPayoutEvent(String payoutBatchId) {
        releases.findByPaypalPayoutBatchId(payoutBatchId).ifPresent(r -> advance(r.getId()));
    }

    // ---------------------------------------------------------------------------------------

    /** @return true if the state changed and another step may run right away. */
    private boolean step(UUID releaseId) {
        Release r = load(releaseId);
        try {
            return switch (r.getStatus()) {
                case VERIFIED -> capture(r);
                case CAPTURE_PENDING -> checkCapture(r);
                case CAPTURED -> payout(r);
                case PAYOUT_PENDING -> checkPayout(r);
                default -> false;
            };
        } catch (PayPalApiException e) {
            if (e.status() >= 500) {
                // Transient: leave the state alone; the poller retries with the same request id.
                log.warn("PayPal {} on release {}, will retry: {}", e.status(), releaseId, e.getMessage());
                return false;
            }
            log.warn("PayPal rejected release {} step {}: {}", releaseId, r.getStatus(), e.getMessage());
            fail(r, e.errorName() != null ? e.errorName() : "HTTP_" + e.status());
            return false;
        }
    }

    private boolean capture(Release r) {
        PaymentAuthorization auth = r.getAuthorization();
        Capture cap = paypal.captureAuthorization(auth.getPaypalAuthorizationId(), r.getCaptureRequestId(),
                money(r), r.isFinalCapture(), r.getId().toString());

        // Never trust the 2xx alone: read the capture back and check it belongs to THIS authorization.
        Capture confirmed = paypal.getCapture(cap.id());
        String parent = confirmed.parentAuthorizationId();
        if (parent != null && !parent.equals(auth.getPaypalAuthorizationId())) {
            r.setPaypalCaptureId(cap.id());
            fail(r, "CAPTURE_BELONGS_TO_OTHER_AUTHORIZATION " + parent);
            return false;
        }
        if (confirmed.amount() != null && confirmed.amount().value() != null
                && new java.math.BigDecimal(confirmed.amount().value()).compareTo(r.getAmount()) != 0) {
            r.setPaypalCaptureId(cap.id());
            fail(r, "CAPTURE_AMOUNT_MISMATCH " + confirmed.amount().value());
            return false;
        }

        r.setPaypalCaptureId(confirmed.id());
        auth.setCapturedAmount(auth.getCapturedAmount().add(r.getAmount()));
        syncAuthorizationStatus(auth);

        ReleaseStatus next = switch (confirmed.status()) {
            case "COMPLETED" -> ReleaseStatus.CAPTURED;
            case "PENDING" -> ReleaseStatus.CAPTURE_PENDING;
            default -> ReleaseStatus.CAPTURE_FAILED;
        };
        r.moveTo(next);
        auditRelease(r, "CAPTURE_" + confirmed.status(), AuditService.PAYPAL,
                Map.of("captureId", confirmed.id(), "amount", r.getAmount(), "authorizationStatus", auth.getStatus()));
        return next == ReleaseStatus.CAPTURED;
    }

    private boolean checkCapture(Release r) {
        Capture c = paypal.getCapture(r.getPaypalCaptureId());
        if ("COMPLETED".equals(c.status())) {
            r.moveTo(ReleaseStatus.CAPTURED);
            auditRelease(r, "CAPTURE_COMPLETED", AuditService.PAYPAL, Map.of("captureId", c.id()));
            return true;
        }
        if ("DECLINED".equals(c.status()) || "FAILED".equals(c.status())) {
            fail(r, "CAPTURE_" + c.status());
        }
        return false;
    }

    private boolean payout(Release r) {
        String platform = props.platformEmail();
        if (platform != null && platform.equalsIgnoreCase(r.getPayeeEmail())) {
            fail(r, "SELF_PAY_NOT_ALLOWED (blocked before calling PayPal)");
            return false;
        }
        PayoutBatch batch = paypal.createPayout(r.getPayoutRequestId(), r.getPayeeEmail(), money(r),
                "ProofPay: " + r.getMilestone().getTitle());
        r.setPaypalPayoutBatchId(batch.batchHeader().payoutBatchId());
        r.moveTo(ReleaseStatus.PAYOUT_PENDING);
        auditRelease(r, "PAYOUT_SENT", AuditService.PAYPAL,
                Map.of("payoutBatchId", batch.batchHeader().payoutBatchId(), "batchStatus", batch.batchHeader().batchStatus()));
        return true;
    }

    private boolean checkPayout(Release r) {
        PayoutBatch batch = paypal.getPayoutBatch(r.getPaypalPayoutBatchId());
        PayoutItemDetails item = batch.firstItem();
        if (item != null) r.setPaypalPayoutItemId(item.payoutItemId());
        String itemStatus = item == null ? null : item.transactionStatus();

        if ("SUCCESS".equals(itemStatus)) {
            r.moveTo(ReleaseStatus.PAID);
            r.getMilestone().setStatus(MilestoneStatus.PAID);
            auditRelease(r, "PAYOUT_SUCCEEDED", AuditService.PAYPAL, Map.of("payoutItemId", item.payoutItemId()));
            completeJobIfAllPaid(r.getJob());
            return false;
        }
        if (PAYOUT_ITEM_FAILED.contains(itemStatus) || "DENIED".equals(batch.batchHeader().batchStatus())) {
            String reason = item != null && item.errors() != null && item.errors().name() != null
                    ? item.errors().name() : "PAYOUT_" + (itemStatus != null ? itemStatus : batch.batchHeader().batchStatus());
            fail(r, reason);
        }
        return false; // still PENDING / UNCLAIMED / ONHOLD: wait for webhook or poller
    }

    private void fail(Release r, String reason) {
        ReleaseStatus target = switch (r.getStatus()) {
            case VERIFIED, CAPTURE_PENDING -> ReleaseStatus.CAPTURE_FAILED;
            default -> ReleaseStatus.PAYOUT_FAILED;
        };
        r.moveTo(target);
        r.setFailureReason(reason);
        auditRelease(r, target.name(), AuditService.PAYPAL, Map.of("reason", reason));
        log.warn("Release {} -> {}: {}", r.getId(), target, reason);
    }

    /** Read the authorization back from PayPal rather than assuming what our capture did to it. */
    private void syncAuthorizationStatus(PaymentAuthorization auth) {
        Authorization a = paypal.getAuthorization(auth.getPaypalAuthorizationId());
        switch (a.status()) {
            case "CAPTURED" -> auth.setStatus(AuthorizationStatus.CAPTURED);
            case "PARTIALLY_CAPTURED" -> auth.setStatus(AuthorizationStatus.PARTIALLY_CAPTURED);
            case "VOIDED" -> auth.setStatus(AuthorizationStatus.VOIDED);
            default -> { /* CREATED / PENDING: leave as is */ }
        }
    }

    private void completeJobIfAllPaid(Job job) {
        boolean allPaid = job.getMilestones().stream().allMatch(m -> m.getStatus() == MilestoneStatus.PAID);
        if (allPaid) {
            job.setStatus(JobStatus.COMPLETED);
            audit.record(job.getId(), null, null, "JOB_COMPLETED", AuditService.SYSTEM, null);
        }
    }

    private void auditRelease(Release r, String type, String actor, Map<String, ?> detail) {
        audit.record(r.getJob().getId(), r.getMilestone().getId(), r.getId(), type, actor, detail);
    }

    private Release load(UUID id) {
        return releases.findById(id).orElseThrow(() -> new NoSuchElementException("Release not found: " + id));
    }

    private static Money money(Release r) {
        return new Money(r.getCurrency(), r.getAmount().setScale(2).toPlainString());
    }
}
