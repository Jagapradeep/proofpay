package dev.proofpay.release;

import dev.proofpay.domain.*;
import dev.proofpay.paypal.PayPalProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Deterministic rules that every release must pass. No LLM involved:
 * "LLM proposes, code disposes."
 */
@Component
public class PolicyGate {

    public static final double MIN_CONFIDENCE = 0.8;

    private static final Set<AuthorizationStatus> USABLE_AUTH =
            Set.of(AuthorizationStatus.AUTHORIZED, AuthorizationStatus.PARTIALLY_CAPTURED);

    private final PayPalProperties props;

    public PolicyGate(PayPalProperties props) {
        this.props = props;
    }

    public record Decision(boolean approved, List<String> reasons) {}

    public Decision evaluate(ReleaseProposal p, Milestone milestone, PaymentAuthorization auth, boolean releaseExists) {
        List<String> reasons = new ArrayList<>();
        Job job = milestone.getJob();

        if (p.injectionFlagged()) {
            reasons.add("Prompt injection detected in the deliverable");
        }
        if (!(p.confidence() >= MIN_CONFIDENCE)) { // written this way so NaN fails too
            reasons.add("Confidence " + p.confidence() + " is below " + MIN_CONFIDENCE);
        }
        if (milestone.getStatus() != MilestoneStatus.SUBMITTED) {
            reasons.add("Milestone is " + milestone.getStatus() + ", expected SUBMITTED");
        }
        if (releaseExists) {
            reasons.add("Milestone already has a release");
        }
        if (job.getStatus() != JobStatus.FUNDED) {
            reasons.add("Job is " + job.getStatus() + ", expected FUNDED");
        }
        BigDecimal amount = p.amount();
        if (amount == null || amount.signum() <= 0) {
            reasons.add("Amount must be positive");
        } else {
            if (amount.compareTo(milestone.getAmount()) > 0) {
                reasons.add("Amount " + amount + " exceeds milestone amount " + milestone.getAmount());
            }
            if (auth != null && amount.compareTo(auth.remaining()) > 0) {
                reasons.add("Amount " + amount + " exceeds remaining authorization " + auth.remaining());
            }
        }
        if (auth == null || !USABLE_AUTH.contains(auth.getStatus())) {
            reasons.add("No usable PayPal authorization for this job");
        } else if (auth.getExpiresAt() != null && auth.getExpiresAt().isBefore(Instant.now())) {
            reasons.add("PayPal authorization expired at " + auth.getExpiresAt());
        }
        String platform = props.platformEmail();
        if (platform != null && !platform.isBlank() && platform.equalsIgnoreCase(job.getFreelancerEmail())) {
            reasons.add("Payee is the platform merchant account (self-pay)");
        }
        return new Decision(reasons.isEmpty(), List.copyOf(reasons));
    }
}
