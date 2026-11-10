package dev.proofpay.job;

import dev.proofpay.audit.AuditService;
import dev.proofpay.domain.*;
import dev.proofpay.domain.Repositories.JobRepository;
import dev.proofpay.domain.Repositories.PaymentAuthorizationRepository;
import dev.proofpay.paypal.PayPalApiException;
import dev.proofpay.paypal.PayPalClient;
import dev.proofpay.paypal.PayPalDtos.Authorization;
import dev.proofpay.paypal.PayPalDtos.Money;
import dev.proofpay.paypal.PayPalDtos.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/** Client funds a job: PayPal order (intent AUTHORIZE) -> buyer approves -> authorization held. */
@Service
public class FundingService {

    private final JobRepository jobs;
    private final PaymentAuthorizationRepository authorizations;
    private final PayPalClient paypal;
    private final AuditService audit;

    public FundingService(JobRepository jobs, PaymentAuthorizationRepository authorizations,
                          PayPalClient paypal, AuditService audit) {
        this.jobs = jobs;
        this.authorizations = authorizations;
        this.paypal = paypal;
        this.audit = audit;
    }

    public record OrderInfo(String orderId, String approveUrl, String status) {}

    @Transactional
    public OrderInfo createOrder(UUID jobId) {
        Job job = job(jobId);
        if (!Set.of(JobStatus.DRAFT, JobStatus.AWAITING_FUNDING).contains(job.getStatus())) {
            throw new IllegalStateException("Job is " + job.getStatus() + ", cannot create a funding order");
        }
        PaymentAuthorization auth = authorizations.findByJobId(jobId)
                .orElseGet(() -> authorizations.save(new PaymentAuthorization(job)));

        // Same persisted request id on every call: a double click returns the same order.
        Order order = paypal.createAuthorizeOrder(auth.getOrderRequestId(), jobId.toString(), job.getTitle(),
                new Money(job.getCurrency(), job.getTotalAmount().setScale(2).toPlainString()));
        auth.setPaypalOrderId(order.id());
        job.setStatus(JobStatus.AWAITING_FUNDING);
        audit.record(jobId, null, null, "ORDER_CREATED", AuditService.CLIENT,
                Map.of("orderId", order.id(), "amount", job.getTotalAmount()));
        return new OrderInfo(order.id(), order.approveLink(), order.status());
    }

    /** Called after the buyer approved (JS SDK onApprove or the return_url). */
    @Transactional
    public PaymentAuthorization authorize(UUID jobId) {
        Job job = job(jobId);
        PaymentAuthorization auth = authorizations.findByJobId(jobId)
                .orElseThrow(() -> new IllegalStateException("No funding order for job " + jobId));
        if (auth.getStatus() != AuthorizationStatus.ORDER_CREATED) {
            return auth; // already done; safe to call twice
        }

        // Derived from the order's own random request id, so it is unique to this order.
        UUID requestId = UUID.nameUUIDFromBytes(("authorize:" + auth.getOrderRequestId()).getBytes(StandardCharsets.UTF_8));
        Order order;
        try {
            order = paypal.authorizeOrder(auth.getPaypalOrderId(), requestId);
        } catch (PayPalApiException e) {
            if (e.status() != 422) throw e;
            order = paypal.getOrder(auth.getPaypalOrderId()); // e.g. ORDER_ALREADY_AUTHORIZED
        }
        Authorization a = order.firstAuthorization();
        if (a == null) {
            throw new IllegalStateException("Order " + order.id() + " is " + order.status() + ", buyer has not approved yet");
        }

        // Confirm by reading the authorization itself.
        Authorization confirmed = paypal.getAuthorization(a.id());
        if (!"CREATED".equals(confirmed.status()) && !"PENDING".equals(confirmed.status())) {
            throw new IllegalStateException("Authorization " + a.id() + " is " + confirmed.status());
        }
        auth.setPaypalAuthorizationId(confirmed.id());
        auth.setStatus(AuthorizationStatus.AUTHORIZED);
        if (confirmed.expirationTime() != null) auth.setExpiresAt(Instant.parse(confirmed.expirationTime()));
        job.setStatus(JobStatus.FUNDED);
        audit.record(jobId, null, null, "FUNDS_AUTHORIZED", AuditService.PAYPAL,
                Map.of("authorizationId", confirmed.id(), "status", confirmed.status(),
                        "expiresAt", String.valueOf(confirmed.expirationTime())));
        return auth;
    }

    /** Cancel: release the hold on the client's funds. */
    @Transactional
    public void voidFunding(UUID jobId) {
        Job job = job(jobId);
        PaymentAuthorization auth = authorizations.findByJobId(jobId)
                .orElseThrow(() -> new IllegalStateException("Job " + jobId + " has no funding"));
        if (auth.getPaypalAuthorizationId() == null) {
            throw new IllegalStateException("Nothing authorized yet for job " + jobId);
        }
        paypal.voidAuthorization(auth.getPaypalAuthorizationId());
        auth.setStatus(AuthorizationStatus.VOIDED);
        job.setStatus(JobStatus.CANCELLED);
        audit.record(jobId, null, null, "AUTHORIZATION_VOIDED", AuditService.CLIENT,
                Map.of("authorizationId", auth.getPaypalAuthorizationId(), "capturedBeforeVoid", auth.getCapturedAmount()));
    }

    /** Webhook PAYMENT.AUTHORIZATION.VOIDED (e.g. voided from the PayPal dashboard). */
    @Transactional
    public void onAuthorizationVoided(String paypalAuthorizationId) {
        authorizations.findByPaypalAuthorizationId(paypalAuthorizationId).ifPresent(auth -> {
            auth.setStatus(AuthorizationStatus.VOIDED);
            audit.record(auth.getJob().getId(), null, null, "AUTHORIZATION_VOIDED", AuditService.PAYPAL,
                    Map.of("authorizationId", paypalAuthorizationId));
        });
    }

    private Job job(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new NoSuchElementException("Job not found: " + id));
    }
}
