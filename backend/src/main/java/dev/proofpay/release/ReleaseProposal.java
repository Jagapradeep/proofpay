package dev.proofpay.release;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the verifier agent is allowed to ask for: "pay this milestone this much, here is why".
 * The LLM proposes; {@link PolicyGate} decides.
 */
public record ReleaseProposal(
        UUID milestoneId,
        BigDecimal amount,
        double confidence,
        boolean injectionFlagged,
        List<String> evidence) {}
