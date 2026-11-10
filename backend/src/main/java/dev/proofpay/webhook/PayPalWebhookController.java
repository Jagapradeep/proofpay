package dev.proofpay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proofpay.domain.Repositories.WebhookEventRepository;
import dev.proofpay.domain.WebhookEvent;
import dev.proofpay.job.FundingService;
import dev.proofpay.paypal.PayPalClient;
import dev.proofpay.paypal.PayPalProperties;
import dev.proofpay.release.ReleaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * PayPal webhooks. Events only trigger a re-check: the release service reads the real
 * state back from PayPal, so a forged or stale event can't move a release on its own.
 */
@RestController
@RequestMapping("/api/webhooks/paypal")
public class PayPalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookController.class);

    private final PayPalClient paypal;
    private final PayPalProperties props;
    private final ReleaseService releases;
    private final FundingService funding;
    private final WebhookEventRepository seen;
    private final ObjectMapper json;

    public PayPalWebhookController(PayPalClient paypal, PayPalProperties props, ReleaseService releases,
                                   FundingService funding, WebhookEventRepository seen, ObjectMapper json) {
        this.paypal = paypal;
        this.props = props;
        this.releases = releases;
        this.funding = funding;
        this.seen = seen;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader Map<String, String> headers, @RequestBody String rawBody)
            throws IOException {
        if (!props.webhookVerificationConfigured()) {
            log.warn("PAYPAL_WEBHOOK_ID not set; ignoring webhook (the poller still tracks releases)");
            return ResponseEntity.accepted().build();
        }
        if (!paypal.verifyWebhookSignature(headers, rawBody)) {
            log.warn("Rejected webhook with an invalid signature");
            return ResponseEntity.badRequest().build();
        }

        JsonNode event = json.readTree(rawBody);
        String id = event.path("id").asText();
        String type = event.path("event_type").asText();
        if (seen.existsById(id)) {
            return ResponseEntity.ok().build(); // PayPal retry of an event we already handled
        }
        seen.save(new WebhookEvent(id, type));

        JsonNode resource = event.path("resource");
        log.info("PayPal webhook {} {}", type, id);
        switch (type) {
            case "PAYMENT.CAPTURE.COMPLETED", "PAYMENT.CAPTURE.PENDING", "PAYMENT.CAPTURE.DENIED" ->
                    releases.onCaptureEvent(resource.path("id").asText());
            case "PAYMENT.AUTHORIZATION.VOIDED" ->
                    funding.onAuthorizationVoided(resource.path("id").asText());
            default -> {
                if (type.startsWith("PAYMENT.PAYOUTS-ITEM.") || type.startsWith("PAYMENT.PAYOUTSBATCH.")) {
                    String batchId = resource.path("payout_batch_id").asText(
                            resource.path("batch_header").path("payout_batch_id").asText());
                    releases.onPayoutEvent(batchId);
                }
            }
        }
        return ResponseEntity.ok().build();
    }
}
