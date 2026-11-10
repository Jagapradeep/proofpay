package dev.proofpay.paypal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proofpay.paypal.PayPalDtos.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin wrapper over the PayPal REST calls verified in the sandbox spike.
 * Every money-moving call takes a PayPal-Request-Id that the caller persisted beforehand.
 */
@Component
public class PayPalClient {

    private final RestClient http;
    private final PayPalTokenProvider tokens;
    private final PayPalProperties props;
    private final ObjectMapper json;

    public PayPalClient(RestClient.Builder builder, PayPalTokenProvider tokens, PayPalProperties props, ObjectMapper json) {
        this.tokens = tokens;
        this.props = props;
        this.json = json;
        this.http = builder
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((req, body, exec) -> {
                    req.getHeaders().setBearerAuth(tokens.token());
                    return exec.execute(req, body);
                })
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    if (res.getStatusCode().value() == 401) tokens.invalidate();
                    throw toException(res.getStatusCode().value(), res.getBody());
                })
                .build();
    }

    // ---------- Orders v2 ----------

    /** Creates an AUTHORIZE order: the buyer approves, the money is held, nothing is charged yet. */
    public Order createAuthorizeOrder(UUID requestId, String jobId, String description, Money amount) {
        var body = new CreateOrderRequest(
                "AUTHORIZE",
                List.of(new PurchaseUnitRequest(jobId, truncate(description, 127), jobId, amount)),
                new PaymentSource(new PayPalSource(new ExperienceContext(
                        props.returnUrl(), props.cancelUrl(), "CONTINUE", "NO_SHIPPING", "ProofPay"))));
        return http.post().uri("/v2/checkout/orders")
                .header("PayPal-Request-Id", requestId.toString())
                .body(body).retrieve().body(Order.class);
    }

    public Order getOrder(String orderId) {
        return http.get().uri("/v2/checkout/orders/{id}", orderId).retrieve().body(Order.class);
    }

    /** After buyer approval: turns the order into an authorization (valid ~29 days). */
    public Order authorizeOrder(String orderId, UUID requestId) {
        return http.post().uri("/v2/checkout/orders/{id}/authorize", orderId)
                .header("PayPal-Request-Id", requestId.toString())
                .body(Map.of()).retrieve().body(Order.class);
    }

    // ---------- Payments v2 ----------

    public Authorization getAuthorization(String authorizationId) {
        return http.get().uri("/v2/payments/authorizations/{id}", authorizationId)
                .retrieve().body(Authorization.class);
    }

    /** Partial capture. final_capture=true on the last milestone closes the authorization. */
    public Capture captureAuthorization(String authorizationId, UUID requestId, Money amount,
                                        boolean finalCapture, String invoiceId) {
        return http.post().uri("/v2/payments/authorizations/{id}/capture", authorizationId)
                .header("PayPal-Request-Id", requestId.toString())
                .header("Prefer", "return=representation")
                .body(new CaptureRequest(amount, finalCapture, invoiceId, "ProofPay milestone release"))
                .retrieve().body(Capture.class);
    }

    public Capture getCapture(String captureId) {
        return http.get().uri("/v2/payments/captures/{id}", captureId).retrieve().body(Capture.class);
    }

    /** 204 on an uncaptured authorization; 422 if it was already captured. */
    public void voidAuthorization(String authorizationId) {
        http.post().uri("/v2/payments/authorizations/{id}/void", authorizationId)
                .retrieve().toBodilessEntity();
    }

    // ---------- Payouts v1 ----------

    /** Async: the batch starts PENDING and settles SUCCESS / DENIED a minute or two later. */
    public PayoutBatch createPayout(UUID senderBatchId, String receiverEmail, Money amount, String note) {
        var body = new PayoutRequest(
                new SenderBatchHeader(senderBatchId.toString(), "You got paid via ProofPay",
                        "A milestone was verified and released."),
                List.of(new PayoutItem("EMAIL", new PayoutAmount(amount.value(), amount.currencyCode()),
                        receiverEmail, note, senderBatchId.toString())));
        return http.post().uri("/v1/payments/payouts")
                .header("PayPal-Request-Id", senderBatchId.toString())
                .body(body).retrieve().body(PayoutBatch.class);
    }

    public PayoutBatch getPayoutBatch(String payoutBatchId) {
        return http.get().uri("/v1/payments/payouts/{id}", payoutBatchId).retrieve().body(PayoutBatch.class);
    }

    // ---------- Webhooks ----------

    /**
     * Asks PayPal whether a webhook delivery is genuine. The raw body is spliced in unchanged,
     * because re-serialising the event can alter it and break verification.
     */
    public boolean verifyWebhookSignature(Map<String, String> headers, String rawBody) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("transmission_id", headers.get("paypal-transmission-id"));
        fields.put("transmission_time", headers.get("paypal-transmission-time"));
        fields.put("cert_url", headers.get("paypal-cert-url"));
        fields.put("auth_algo", headers.get("paypal-auth-algo"));
        fields.put("transmission_sig", headers.get("paypal-transmission-sig"));
        fields.put("webhook_id", props.webhookId());
        try {
            String prefix = json.writeValueAsString(fields);
            String body = prefix.substring(0, prefix.length() - 1) + ",\"webhook_event\":" + rawBody + "}";
            JsonNode res = http.post().uri("/v1/notifications/verify-webhook-signature")
                    .body(body).retrieve().body(JsonNode.class);
            return res != null && "SUCCESS".equals(res.path("verification_status").asText());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- helpers ----------

    private PayPalApiException toException(int status, InputStream bodyStream) throws IOException {
        String body = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
        String name = null, debugId = null;
        try {
            JsonNode node = json.readTree(body);
            name = node.path("name").asText(node.path("error").asText(null));
            debugId = node.path("debug_id").asText(null);
        } catch (IOException ignored) {
            // non-JSON error body
        }
        return new PayPalApiException(status, name, debugId, body);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
