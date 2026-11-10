package dev.proofpay.domain;

import java.util.Set;

/**
 * Release state machine.
 * VERIFIED -> CAPTURED (or CAPTURE_PENDING first) -> PAYOUT_PENDING -> PAID | PAYOUT_FAILED.
 * PAYOUT_FAILED can be retried; the captured money stays with the platform meanwhile.
 */
public enum ReleaseStatus {
    VERIFIED, CAPTURE_PENDING, CAPTURED, CAPTURE_FAILED, PAYOUT_PENDING, PAID, PAYOUT_FAILED;

    public Set<ReleaseStatus> next() {
        return switch (this) {
            case VERIFIED -> Set.of(CAPTURE_PENDING, CAPTURED, CAPTURE_FAILED);
            case CAPTURE_PENDING -> Set.of(CAPTURED, CAPTURE_FAILED);
            case CAPTURED -> Set.of(PAYOUT_PENDING, PAYOUT_FAILED);
            case PAYOUT_PENDING -> Set.of(PAID, PAYOUT_FAILED);
            case PAYOUT_FAILED -> Set.of(CAPTURED); // retry: back to "captured, not yet paid out"
            case PAID, CAPTURE_FAILED -> Set.of();
        };
    }

    public boolean canMoveTo(ReleaseStatus target) {
        return next().contains(target);
    }
}
