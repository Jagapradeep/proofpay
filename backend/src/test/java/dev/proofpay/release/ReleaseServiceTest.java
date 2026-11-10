package dev.proofpay.release;

import dev.proofpay.audit.AuditService;
import dev.proofpay.domain.*;
import dev.proofpay.domain.Repositories.*;
import dev.proofpay.paypal.PayPalApiException;
import dev.proofpay.paypal.PayPalClient;
import dev.proofpay.paypal.PayPalDtos.*;
import dev.proofpay.paypal.PayPalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The release state machine against a fake PayPal: every path we rely on in the demo. */
class ReleaseServiceTest {

    private static final String PLATFORM = "platform@biz.example.com";
    private static final String AUTH_ID = "AUTH-1";

    private final Map<UUID, Release> store = new HashMap<>();
    private ReleaseRepository releases;
    private MilestoneRepository milestones;
    private PaymentAuthorizationRepository authorizations;
    private PayPalClient paypal;
    private ReleaseService service;

    private Job job;
    private Milestone m1;
    private Milestone m2;
    private PaymentAuthorization auth;

    @BeforeEach
    void setUp() {
        releases = mock(ReleaseRepository.class);
        milestones = mock(MilestoneRepository.class);
        authorizations = mock(PaymentAuthorizationRepository.class);
        paypal = mock(PayPalClient.class);
        var props = new PayPalProperties("https://x", "id", "s", PLATFORM, "", "r", "c");
        service = new ReleaseService(releases, milestones, authorizations, mock(JobRepository.class),
                new PolicyGate(props), paypal, props, mock(AuditService.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));

        when(releases.save(any())).thenAnswer(inv -> {
            Release r = inv.getArgument(0);
            store.put(r.getId(), r);
            return r;
        });
        when(releases.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        when(releases.findByMilestoneId(any())).thenAnswer(inv -> store.values().stream()
                .filter(r -> r.getMilestone().getId().equals(inv.getArgument(0))).findFirst());

        job = fundedJob("dev@personal.example.com", "40.00", "60.00");
        m1 = job.getMilestones().get(0);
        m2 = job.getMilestones().get(1);
        m1.setStatus(MilestoneStatus.SUBMITTED);
    }

    private Job fundedJob(String freelancer, String... amounts) {
        Job j = new Job("Todo API", "desc", "client@personal.example.com", freelancer, "USD");
        for (String a : amounts) j.addMilestone("m", new BigDecimal(a));
        j.setStatus(JobStatus.FUNDED);
        auth = new PaymentAuthorization(j);
        auth.setPaypalAuthorizationId(AUTH_ID);
        auth.setStatus(AuthorizationStatus.AUTHORIZED);
        auth.setExpiresAt(Instant.now().plusSeconds(86_400));
        for (Milestone m : j.getMilestones()) when(milestones.findById(m.getId())).thenReturn(Optional.of(m));
        when(authorizations.findByJobId(j.getId())).thenReturn(Optional.of(auth));
        return j;
    }

    // ---- fake PayPal answers ----

    private static Capture capture(String status, String amount, String parentAuth) {
        return new Capture("CAP-1", status, new Money("USD", amount), false,
                List.of(new Link("https://api.sandbox.paypal.com/v2/payments/authorizations/" + parentAuth, "up", "GET")));
    }

    private void captureSucceeds(String amount, String authStatus) {
        when(paypal.captureAuthorization(any(), any(), any(), anyBoolean(), any())).thenReturn(capture("COMPLETED", amount, AUTH_ID));
        when(paypal.getCapture("CAP-1")).thenReturn(capture("COMPLETED", amount, AUTH_ID));
        when(paypal.getAuthorization(AUTH_ID)).thenReturn(new Authorization(AUTH_ID, authStatus, null, null));
    }

    private void payoutAccepted() {
        when(paypal.createPayout(any(), any(), any(), any()))
                .thenReturn(new PayoutBatch(new BatchHeader("B-1", "PENDING"), null));
    }

    private void payoutStatus(String batchStatus, String itemStatus, String errorName) {
        when(paypal.getPayoutBatch("B-1")).thenReturn(new PayoutBatch(new BatchHeader("B-1", batchStatus),
                List.of(new PayoutItemDetails("I-1", itemStatus, errorName == null ? null : new ErrorDetail(errorName, "x")))));
    }

    private ReleaseProposal good(Milestone m, String amount) {
        return new ReleaseProposal(m.getId(), new BigDecimal(amount), 0.93, false, List.of("evidence"));
    }

    private Release only() {
        return store.values().iterator().next();
    }

    // ---- tests ----

    @Test
    void happyPathCapturesThenPaysOut() {
        captureSucceeds("40.00", "PARTIALLY_CAPTURED");
        payoutAccepted();
        payoutStatus("PROCESSING", "PENDING", null);

        var result = service.propose(good(m1, "40.00"), AuditService.AGENT);

        Release r = only();
        assertThat(result.decision().approved()).isTrue();
        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.PAYOUT_PENDING);
        assertThat(r.isFinalCapture()).isFalse();
        assertThat(auth.getCapturedAmount()).isEqualByComparingTo("40.00");
        assertThat(auth.getStatus()).isEqualTo(AuthorizationStatus.PARTIALLY_CAPTURED);
        assertThat(m1.getStatus()).isEqualTo(MilestoneStatus.RELEASING);
        verify(paypal).captureAuthorization(eq(AUTH_ID), eq(r.getCaptureRequestId()),
                eq(new Money("USD", "40.00")), eq(false), eq(r.getId().toString()));
        verify(paypal).createPayout(eq(r.getPayoutRequestId()), eq("dev@personal.example.com"), any(), any());

        payoutStatus("SUCCESS", "SUCCESS", null);
        service.advance(r.getId());

        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.PAID);
        assertThat(m1.getStatus()).isEqualTo(MilestoneStatus.PAID);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FUNDED); // milestone 2 still open
    }

    @Test
    void lastMilestoneIsFinalCaptureAndCompletesTheJob() {
        auth.setCapturedAmount(new BigDecimal("40.00"));
        auth.setStatus(AuthorizationStatus.PARTIALLY_CAPTURED);
        m1.setStatus(MilestoneStatus.PAID);
        m2.setStatus(MilestoneStatus.SUBMITTED);
        captureSucceeds("60.00", "CAPTURED");
        payoutAccepted();
        payoutStatus("SUCCESS", "SUCCESS", null);

        service.propose(good(m2, "60.00"), AuditService.AGENT);

        Release r = only();
        assertThat(r.isFinalCapture()).isTrue();
        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.PAID);
        assertThat(auth.getStatus()).isEqualTo(AuthorizationStatus.CAPTURED);
        assertThat(auth.remaining()).isEqualByComparingTo("0");
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        verify(paypal).captureAuthorization(any(), any(), any(), eq(true), any());
    }

    @Test
    void blockedProposalNeverTouchesPayPal() {
        var injected = new ReleaseProposal(m1.getId(), new BigDecimal("40.00"), 0.99, true, List.of());

        var result = service.propose(injected, AuditService.AGENT);

        assertThat(result.decision().approved()).isFalse();
        assertThat(result.releaseId()).isNull();
        assertThat(m1.getStatus()).isEqualTo(MilestoneStatus.NEEDS_REVIEW);
        assertThat(store).isEmpty();
        verifyNoInteractions(paypal);
    }

    @Test
    void secondProposalForSameMilestoneIsBlocked() {
        captureSucceeds("40.00", "PARTIALLY_CAPTURED");
        payoutAccepted();
        payoutStatus("PROCESSING", "PENDING", null);
        service.propose(good(m1, "40.00"), AuditService.AGENT);

        var again = service.propose(good(m1, "40.00"), AuditService.AGENT);

        assertThat(again.decision().approved()).isFalse();
        assertThat(store).hasSize(1);
        verify(paypal, times(1)).captureAuthorization(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void replayedCaptureFromAnotherAuthorizationIsCaught() {
        // The spike gotcha: PayPal answers 2xx with a cached capture that belongs to a different authorization.
        when(paypal.captureAuthorization(any(), any(), any(), anyBoolean(), any())).thenReturn(capture("COMPLETED", "40.00", "AUTH-OLD"));
        when(paypal.getCapture("CAP-1")).thenReturn(capture("COMPLETED", "40.00", "AUTH-OLD"));

        service.propose(good(m1, "40.00"), AuditService.AGENT);

        Release r = only();
        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.CAPTURE_FAILED);
        assertThat(r.getFailureReason()).contains("CAPTURE_BELONGS_TO_OTHER_AUTHORIZATION");
        assertThat(auth.getCapturedAmount()).isEqualByComparingTo("0");
        verify(paypal, never()).createPayout(any(), any(), any(), any());
    }

    @Test
    void captureAmountMismatchIsCaught() {
        when(paypal.captureAuthorization(any(), any(), any(), anyBoolean(), any())).thenReturn(capture("COMPLETED", "4.00", AUTH_ID));
        when(paypal.getCapture("CAP-1")).thenReturn(capture("COMPLETED", "4.00", AUTH_ID));

        service.propose(good(m1, "40.00"), AuditService.AGENT);

        assertThat(only().getStatus()).isEqualTo(ReleaseStatus.CAPTURE_FAILED);
        assertThat(only().getFailureReason()).contains("CAPTURE_AMOUNT_MISMATCH");
    }

    @Test
    void pendingCaptureWaitsThenContinues() {
        when(paypal.captureAuthorization(any(), any(), any(), anyBoolean(), any())).thenReturn(capture("PENDING", "40.00", AUTH_ID));
        when(paypal.getCapture("CAP-1")).thenReturn(capture("PENDING", "40.00", AUTH_ID));
        when(paypal.getAuthorization(AUTH_ID)).thenReturn(new Authorization(AUTH_ID, "PARTIALLY_CAPTURED", null, null));

        service.propose(good(m1, "40.00"), AuditService.AGENT);
        Release r = only();
        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.CAPTURE_PENDING);
        verify(paypal, never()).createPayout(any(), any(), any(), any());

        when(paypal.getCapture("CAP-1")).thenReturn(capture("COMPLETED", "40.00", AUTH_ID));
        payoutAccepted();
        payoutStatus("PROCESSING", "PENDING", null);
        service.advance(r.getId());

        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.PAYOUT_PENDING);
    }

    @Test
    void paypal5xxLeavesStateAndRetryReusesTheSameRequestId() {
        when(paypal.captureAuthorization(any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new PayPalApiException(503, "SERVICE_UNAVAILABLE", "d", "{}"));

        service.propose(good(m1, "40.00"), AuditService.AGENT);
        Release r = only();
        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.VERIFIED);

        reset(paypal);
        captureSucceeds("40.00", "PARTIALLY_CAPTURED");
        payoutAccepted();
        payoutStatus("PROCESSING", "PENDING", null);
        service.advance(r.getId()); // what the poller does

        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.PAYOUT_PENDING);
        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        verify(paypal).captureAuthorization(any(), ids.capture(), any(), anyBoolean(), any());
        assertThat(ids.getValue()).isEqualTo(r.getCaptureRequestId());
    }

    @Test
    void payout4xxFailsThenRetryUsesANewPayoutIdAndNoSecondCapture() {
        captureSucceeds("40.00", "PARTIALLY_CAPTURED");
        when(paypal.createPayout(any(), any(), any(), any()))
                .thenThrow(new PayPalApiException(400, "VALIDATION_ERROR", "d", "{}"));

        service.propose(good(m1, "40.00"), AuditService.AGENT);
        Release r = only();
        UUID firstPayoutId = r.getPayoutRequestId();
        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.PAYOUT_FAILED);
        assertThat(r.getFailureReason()).isEqualTo("VALIDATION_ERROR");

        reset(paypal);
        payoutAccepted();
        payoutStatus("SUCCESS", "SUCCESS", null);
        service.retryPayout(r.getId());

        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.PAID);
        assertThat(r.getPayoutRequestId()).isNotEqualTo(firstPayoutId);
        assertThat(r.getFailureReason()).isNull();
        verify(paypal).createPayout(eq(r.getPayoutRequestId()), any(), any(), any());
        verify(paypal, never()).captureAuthorization(any(), any(), any(), anyBoolean(), any());
        assertThat(auth.getCapturedAmount()).isEqualByComparingTo("40.00"); // captured once only
    }

    @Test
    void deniedPayoutStoresPayPalErrorName() {
        captureSucceeds("40.00", "PARTIALLY_CAPTURED");
        payoutAccepted();
        payoutStatus("DENIED", "FAILED", "SELF_PAY_NOT_ALLOWED");

        service.propose(good(m1, "40.00"), AuditService.AGENT);

        assertThat(only().getStatus()).isEqualTo(ReleaseStatus.PAYOUT_FAILED);
        assertThat(only().getFailureReason()).isEqualTo("SELF_PAY_NOT_ALLOWED");
    }

    @Test
    void payoutStepRefusesToPayThePlatformEvenIfTheGateWasBypassed() {
        Job selfPay = fundedJob(PLATFORM.toUpperCase(), "10.00");
        Milestone m = selfPay.getMilestones().getFirst();
        Release r = releases.save(new Release(m, auth, new BigDecimal("10.00"), true));
        r.moveTo(ReleaseStatus.CAPTURED);

        service.advance(r.getId());

        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.PAYOUT_FAILED);
        assertThat(r.getFailureReason()).startsWith("SELF_PAY_NOT_ALLOWED");
        verify(paypal, never()).createPayout(any(), any(), any(), any());
    }

    @Test
    void finishedReleasesAreLeftAlone() {
        captureSucceeds("40.00", "PARTIALLY_CAPTURED");
        payoutAccepted();
        payoutStatus("SUCCESS", "SUCCESS", null);
        service.propose(good(m1, "40.00"), AuditService.AGENT);
        Release r = only();
        assertThat(r.getStatus()).isEqualTo(ReleaseStatus.PAID);

        clearInvocations(paypal);
        service.advance(r.getId()); // a late webhook or poll

        verifyNoInteractions(paypal);
    }
}
