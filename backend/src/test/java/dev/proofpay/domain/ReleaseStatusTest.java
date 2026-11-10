package dev.proofpay.domain;

import org.junit.jupiter.api.Test;

import static dev.proofpay.domain.ReleaseStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReleaseStatusTest {

    @Test
    void happyPath() {
        assertThat(VERIFIED.canMoveTo(CAPTURED)).isTrue();
        assertThat(CAPTURED.canMoveTo(PAYOUT_PENDING)).isTrue();
        assertThat(PAYOUT_PENDING.canMoveTo(PAID)).isTrue();
    }

    @Test
    void pendingCaptureAndPayoutRetry() {
        assertThat(VERIFIED.canMoveTo(CAPTURE_PENDING)).isTrue();
        assertThat(CAPTURE_PENDING.canMoveTo(CAPTURED)).isTrue();
        assertThat(PAYOUT_PENDING.canMoveTo(PAYOUT_FAILED)).isTrue();
        assertThat(PAYOUT_FAILED.canMoveTo(CAPTURED)).isTrue();
    }

    @Test
    void cannotSkipCaptureOrLeaveTerminalStates() {
        assertThat(VERIFIED.canMoveTo(PAYOUT_PENDING)).isFalse();
        assertThat(VERIFIED.canMoveTo(PAID)).isFalse();
        assertThat(PAID.next()).isEmpty();
        assertThat(CAPTURE_FAILED.next()).isEmpty();
    }
}
