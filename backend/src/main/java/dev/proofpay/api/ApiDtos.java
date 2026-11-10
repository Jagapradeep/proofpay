package dev.proofpay.api;

import dev.proofpay.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {
    private ApiDtos() {}

    // ---- requests ----

    public record CreateJobRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank String description,
            @NotBlank @Email String clientEmail,
            @NotBlank @Email String freelancerEmail,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotEmpty @Size(max = 5) List<@Valid MilestoneRequest> milestones) {}

    public record MilestoneRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull @DecimalMin("1.00") @Digits(integer = 8, fraction = 2) BigDecimal amount) {}

    public record SubmitRequest(@NotBlank @Pattern(regexp = "https://github\\.com/.+") String deliverableUrl) {}

    /** Same shape the verifier agent's proposeRelease tool will send. */
    public record ProposeReleaseRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
            boolean injectionFlagged,
            List<String> evidence) {}

    // ---- responses ----

    public record MilestoneView(UUID id, int seq, String title, BigDecimal amount, MilestoneStatus status,
                                String deliverableUrl, String criteriaJson) {
        static MilestoneView of(Milestone m) {
            return new MilestoneView(m.getId(), m.getSeq(), m.getTitle(), m.getAmount(), m.getStatus(),
                    m.getDeliverableUrl(), m.getCriteriaJson());
        }
    }

    public record AuthorizationView(String paypalOrderId, String paypalAuthorizationId, BigDecimal amount,
                                    BigDecimal capturedAmount, BigDecimal remaining, AuthorizationStatus status,
                                    Instant expiresAt) {
        static AuthorizationView of(PaymentAuthorization a) {
            return a == null ? null : new AuthorizationView(a.getPaypalOrderId(), a.getPaypalAuthorizationId(),
                    a.getAmount(), a.getCapturedAmount(), a.remaining(), a.getStatus(), a.getExpiresAt());
        }
    }

    public record JobView(UUID id, String title, String description, String clientEmail, String freelancerEmail,
                          String currency, BigDecimal totalAmount, JobStatus status, Instant createdAt,
                          List<MilestoneView> milestones, AuthorizationView funding) {
        static JobView of(Job j, PaymentAuthorization a) {
            return new JobView(j.getId(), j.getTitle(), j.getDescription(), j.getClientEmail(), j.getFreelancerEmail(),
                    j.getCurrency(), j.getTotalAmount(), j.getStatus(), j.getCreatedAt(),
                    j.getMilestones().stream().map(MilestoneView::of).toList(), AuthorizationView.of(a));
        }
    }

    /** One row per release for the AG Grid ledger. */
    public record LedgerRow(UUID releaseId, UUID jobId, String jobTitle, int milestoneSeq, String milestoneTitle,
                            BigDecimal amount, String currency, String payeeEmail, ReleaseStatus status,
                            boolean finalCapture, String paypalAuthorizationId, String paypalCaptureId,
                            String paypalPayoutBatchId, String failureReason, Instant createdAt, Instant updatedAt) {
        static LedgerRow of(Release r) {
            return new LedgerRow(r.getId(), r.getJob().getId(), r.getJob().getTitle(), r.getMilestone().getSeq(),
                    r.getMilestone().getTitle(), r.getAmount(), r.getCurrency(), r.getPayeeEmail(), r.getStatus(),
                    r.isFinalCapture(), r.getAuthorization().getPaypalAuthorizationId(), r.getPaypalCaptureId(),
                    r.getPaypalPayoutBatchId(), r.getFailureReason(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    public record AuditView(Long id, UUID jobId, UUID milestoneId, UUID releaseId, String type, String actor,
                            String detail, Instant createdAt) {
        static AuditView of(AuditEvent e) {
            return new AuditView(e.getId(), e.getJobId(), e.getMilestoneId(), e.getReleaseId(), e.getType(),
                    e.getActor(), e.getDetail(), e.getCreatedAt());
        }
    }

    public record ProposeReleaseResponse(boolean approved, List<String> reasons, UUID releaseId) {}

    public record ApiError(int status, String error, String message) {}
}
