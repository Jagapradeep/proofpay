package dev.proofpay.release;

import dev.proofpay.domain.*;
import dev.proofpay.paypal.PayPalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyGateTest {

    private static final String PLATFORM = "platform@business.example.com";

    private final PolicyGate gate = new PolicyGate(
            new PayPalProperties("https://api-m.sandbox.paypal.com", "id", "secret", PLATFORM, "", "r", "c"));

    private Job job;
    private Milestone m1;
    private PaymentAuthorization auth;

    @BeforeEach
    void setUp() {
        job = new Job("Landing page", "Build it", "client@personal.example.com", "dev@personal.example.com", "USD");
        m1 = job.addMilestone("Scaffold", new BigDecimal("40.00"));
        job.addMilestone("Final", new BigDecimal("60.00"));
        job.setStatus(JobStatus.FUNDED);
        m1.setStatus(MilestoneStatus.SUBMITTED);
        auth = new PaymentAuthorization(job);
        auth.setStatus(AuthorizationStatus.AUTHORIZED);
        auth.setExpiresAt(Instant.now().plusSeconds(86_400));
    }

    private ReleaseProposal proposal(String amount, double confidence, boolean injected) {
        return new ReleaseProposal(m1.getId(), new BigDecimal(amount), confidence, injected, List.of("README has setup steps"));
    }

    @Test
    void approvesAGoodProposal() {
        var d = gate.evaluate(proposal("40.00", 0.92, false), m1, auth, false);
        assertThat(d.approved()).isTrue();
        assertThat(d.reasons()).isEmpty();
    }

    @Test
    void blocksInjectionEvenWithFullConfidence() {
        var d = gate.evaluate(proposal("40.00", 1.0, true), m1, auth, false);
        assertThat(d.approved()).isFalse();
        assertThat(d.reasons()).anyMatch(r -> r.contains("injection"));
    }

    @Test
    void blocksLowConfidence() {
        var d = gate.evaluate(proposal("40.00", 0.79, false), m1, auth, false);
        assertThat(d.approved()).isFalse();
    }

    @Test
    void blocksAmountAboveMilestoneOrRemainingAuthorization() {
        assertThat(gate.evaluate(proposal("100.00", 0.95, false), m1, auth, false).approved()).isFalse();

        auth.setCapturedAmount(new BigDecimal("70.00")); // only 30 left
        var d = gate.evaluate(proposal("40.00", 0.95, false), m1, auth, false);
        assertThat(d.reasons()).anyMatch(r -> r.contains("remaining authorization"));
    }

    @Test
    void blocksSecondReleaseForSameMilestone() {
        assertThat(gate.evaluate(proposal("40.00", 0.95, false), m1, auth, true).approved()).isFalse();
    }

    @Test
    void blocksUnfundedOrExpiredAuthorization() {
        assertThat(gate.evaluate(proposal("40.00", 0.95, false), m1, null, false).approved()).isFalse();

        auth.setExpiresAt(Instant.now().minusSeconds(1));
        assertThat(gate.evaluate(proposal("40.00", 0.95, false), m1, auth, false).approved()).isFalse();
    }

    @Test
    void blocksPayingThePlatformItself() {
        Job selfPay = new Job("x", "y", "client@personal.example.com", PLATFORM.toUpperCase(), "USD");
        Milestone m = selfPay.addMilestone("only", new BigDecimal("10.00"));
        selfPay.setStatus(JobStatus.FUNDED);
        m.setStatus(MilestoneStatus.SUBMITTED);
        PaymentAuthorization a = new PaymentAuthorization(selfPay);
        a.setStatus(AuthorizationStatus.AUTHORIZED);

        var d = gate.evaluate(new ReleaseProposal(m.getId(), new BigDecimal("10.00"), 0.99, false, List.of()), m, a, false);
        assertThat(d.reasons()).anyMatch(r -> r.contains("self-pay"));
    }
}
