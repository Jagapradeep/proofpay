package dev.proofpay.paypal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proofpay.paypal.PayPalDtos.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Contract tests: the exact JSON and headers we send to PayPal, and how we read its answers.
 * (The payout "currency" vs "currency_code" bug found in the sandbox run is pinned here.)
 */
class PayPalClientTest {

    private static final String BASE = "https://api-m.sandbox.paypal.com";

    private MockRestServiceServer server;
    private PayPalTokenProvider tokens;
    private PayPalClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(PayPalTokenProvider.class);
        when(tokens.token()).thenReturn("TEST-TOKEN");
        var props = new PayPalProperties(BASE, "id", "secret", "platform@biz.example.com", "WH-1",
                "http://localhost:3000/fund/return", "http://localhost:3000/fund/cancel");
        client = new PayPalClient(builder, tokens, props, new ObjectMapper());
    }

    @Test
    void payoutUsesCurrencyNotCurrencyCode() {
        UUID batchId = UUID.randomUUID();
        server.expect(requestTo(BASE + "/v1/payments/payouts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer TEST-TOKEN"))
                .andExpect(header("PayPal-Request-Id", batchId.toString()))
                .andExpect(jsonPath("$.sender_batch_header.sender_batch_id").value(batchId.toString()))
                .andExpect(jsonPath("$.items[0].recipient_type").value("EMAIL"))
                .andExpect(jsonPath("$.items[0].receiver").value("dev@personal.example.com"))
                .andExpect(jsonPath("$.items[0].amount.value").value("40.00"))
                .andExpect(jsonPath("$.items[0].amount.currency").value("USD"))
                .andExpect(jsonPath("$.items[0].amount.currency_code").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"batch_header":{"payout_batch_id":"B-1","batch_status":"PENDING"}}"""));

        var batch = client.createPayout(batchId, "dev@personal.example.com", new Money("USD", "40.00"), "note");

        assertThat(batch.batchHeader().payoutBatchId()).isEqualTo("B-1");
        server.verify();
    }

    @Test
    void captureSendsAmountFinalFlagInvoiceAndRequestId() {
        UUID reqId = UUID.randomUUID();
        server.expect(requestTo(BASE + "/v2/payments/authorizations/AUTH-1/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PayPal-Request-Id", reqId.toString()))
                .andExpect(header("Prefer", "return=representation"))
                .andExpect(jsonPath("$.amount.currency_code").value("USD"))
                .andExpect(jsonPath("$.amount.value").value("60.00"))
                .andExpect(jsonPath("$.final_capture").value(true))
                .andExpect(jsonPath("$.invoice_id").value("REL-1"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"id":"CAP-1","status":"COMPLETED","amount":{"currency_code":"USD","value":"60.00"},
                         "links":[{"href":"https://api.sandbox.paypal.com/v2/payments/authorizations/AUTH-1","rel":"up","method":"GET"}]}"""));

        var cap = client.captureAuthorization("AUTH-1", reqId, new Money("USD", "60.00"), true, "REL-1");

        assertThat(cap.status()).isEqualTo("COMPLETED");
        assertThat(cap.parentAuthorizationId()).isEqualTo("AUTH-1");
        server.verify();
    }

    @Test
    void createOrderIsAuthorizeIntentWithExperienceContextAndReadsPayerActionLink() {
        UUID reqId = UUID.randomUUID();
        server.expect(requestTo(BASE + "/v2/checkout/orders"))
                .andExpect(header("PayPal-Request-Id", reqId.toString()))
                .andExpect(jsonPath("$.intent").value("AUTHORIZE"))
                .andExpect(jsonPath("$.purchase_units[0].amount.value").value("100.00"))
                .andExpect(jsonPath("$.purchase_units[0].custom_id").value("JOB-1"))
                .andExpect(jsonPath("$.payment_source.paypal.experience_context.user_action").value("CONTINUE"))
                .andExpect(jsonPath("$.payment_source.paypal.experience_context.return_url").value("http://localhost:3000/fund/return"))
                .andRespond(withSuccess("""
                        {"id":"ORDER-1","status":"PAYER_ACTION_REQUIRED","links":[
                          {"href":"https://api.sandbox.paypal.com/v2/checkout/orders/ORDER-1","rel":"self","method":"GET"},
                          {"href":"https://www.sandbox.paypal.com/checkoutnow?token=ORDER-1","rel":"payer-action","method":"GET"}]}""",
                        MediaType.APPLICATION_JSON));

        var order = client.createAuthorizeOrder(reqId, "JOB-1", "x".repeat(300), new Money("USD", "100.00"));

        assertThat(order.approveLink()).isEqualTo("https://www.sandbox.paypal.com/checkoutnow?token=ORDER-1");
        server.verify();
    }

    @Test
    void authorizeResponseExposesTheAuthorization() {
        server.expect(requestTo(BASE + "/v2/checkout/orders/ORDER-1/authorize"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"id":"ORDER-1","status":"COMPLETED","purchase_units":[{"payments":{"authorizations":[
                          {"id":"AUTH-1","status":"CREATED","amount":{"currency_code":"USD","value":"100.00"},
                           "expiration_time":"2026-12-08T05:00:00Z"}]}}]}"""));

        var auth = client.authorizeOrder("ORDER-1", UUID.randomUUID()).firstAuthorization();

        assertThat(auth.id()).isEqualTo("AUTH-1");
        assertThat(auth.expirationTime()).isEqualTo("2026-12-08T05:00:00Z");
    }

    @Test
    void errorsKeepPayPalNameAndDebugId() {
        server.expect(requestTo(BASE + "/v2/payments/authorizations/AUTH-1/void"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON).body("""
                        {"name":"UNPROCESSABLE_ENTITY","debug_id":"dbg-42","details":[{"issue":"PREVIOUSLY_CAPTURED"}]}"""));

        assertThatThrownBy(() -> client.voidAuthorization("AUTH-1"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.errorName()).isEqualTo("UNPROCESSABLE_ENTITY");
                    assertThat(e.debugId()).isEqualTo("dbg-42");
                    assertThat(e.getMessage()).contains("PREVIOUSLY_CAPTURED");
                });
        verify(tokens, never()).invalidate();
    }

    @Test
    void unauthorizedDropsTheCachedToken() {
        server.expect(requestTo(BASE + "/v2/payments/authorizations/AUTH-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_token\"}"));

        assertThatThrownBy(() -> client.getAuthorization("AUTH-1")).isInstanceOf(PayPalApiException.class);
        verify(tokens).invalidate();
    }

    @Test
    void voidAccepts204() {
        server.expect(requestTo(BASE + "/v2/payments/authorizations/AUTH-1/void"))
                .andRespond(withNoContent());
        client.voidAuthorization("AUTH-1");
        server.verify();
    }

    @Test
    void payoutBatchReadsItemStatusAndErrorName() {
        server.expect(requestTo(BASE + "/v1/payments/payouts/B-1"))
                .andRespond(withSuccess("""
                        {"batch_header":{"payout_batch_id":"B-1","batch_status":"DENIED"},
                         "items":[{"payout_item_id":"I-1","transaction_status":"FAILED",
                                   "errors":{"name":"SELF_PAY_NOT_ALLOWED","message":"no"}}]}""",
                        MediaType.APPLICATION_JSON));

        var batch = client.getPayoutBatch("B-1");

        assertThat(batch.batchHeader().batchStatus()).isEqualTo("DENIED");
        assertThat(batch.firstItem().transactionStatus()).isEqualTo("FAILED");
        assertThat(batch.firstItem().errors().name()).isEqualTo("SELF_PAY_NOT_ALLOWED");
    }

    @Test
    void webhookVerificationSplicesTheRawEventUnchanged() {
        String raw = "{\"id\":\"WH-EVT-1\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"amount\":{\"value\":\"40.00\"}}}";
        server.expect(requestTo(BASE + "/v1/notifications/verify-webhook-signature"))
                .andExpect(content().string(containsString("\"webhook_event\":" + raw)))
                .andExpect(jsonPath("$.webhook_id").value("WH-1"))
                .andExpect(jsonPath("$.transmission_id").value("t-1"))
                .andRespond(withSuccess("{\"verification_status\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));

        boolean ok = client.verifyWebhookSignature(Map.of(
                "paypal-transmission-id", "t-1", "paypal-transmission-time", "now",
                "paypal-cert-url", "https://cert", "paypal-auth-algo", "SHA256withRSA",
                "paypal-transmission-sig", "sig"), raw);

        assertThat(ok).isTrue();
    }
}
