package dev.proofpay.paypal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Only the PayPal fields ProofPay reads or writes. Unknown fields are ignored. */
public final class PayPalDtos {
    private PayPalDtos() {}

    public record Money(@JsonProperty("currency_code") String currencyCode, String value) {}

    public record Link(String href, String rel, String method) {}

    public record ErrorDetail(String name, String message) {}

    // ---- OAuth ----
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("expires_in") long expiresIn) {}

    // ---- Orders v2 ----
    public record CreateOrderRequest(String intent,
                                     @JsonProperty("purchase_units") List<PurchaseUnitRequest> purchaseUnits,
                                     @JsonProperty("payment_source") PaymentSource paymentSource) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PurchaseUnitRequest(@JsonProperty("reference_id") String referenceId,
                                      String description,
                                      @JsonProperty("custom_id") String customId,
                                      Money amount) {}

    public record PaymentSource(PayPalSource paypal) {}

    public record PayPalSource(@JsonProperty("experience_context") ExperienceContext experienceContext) {}

    public record ExperienceContext(@JsonProperty("return_url") String returnUrl,
                                    @JsonProperty("cancel_url") String cancelUrl,
                                    @JsonProperty("user_action") String userAction,
                                    @JsonProperty("shipping_preference") String shippingPreference,
                                    @JsonProperty("brand_name") String brandName) {}

    public record Order(String id, String status, List<Link> links,
                        @JsonProperty("purchase_units") List<PurchaseUnit> purchaseUnits) {
        public String approveLink() {
            if (links == null) return null;
            return links.stream()
                    .filter(l -> "payer-action".equals(l.rel()) || "approve".equals(l.rel()))
                    .map(Link::href).findFirst().orElse(null);
        }

        public Authorization firstAuthorization() {
            if (purchaseUnits == null || purchaseUnits.isEmpty()) return null;
            Payments p = purchaseUnits.getFirst().payments();
            if (p == null || p.authorizations() == null || p.authorizations().isEmpty()) return null;
            return p.authorizations().getFirst();
        }
    }

    public record PurchaseUnit(Payments payments) {}

    public record Payments(List<Authorization> authorizations) {}

    // ---- Payments v2 ----
    public record Authorization(String id, String status, Money amount,
                                @JsonProperty("expiration_time") String expirationTime) {}

    public record CaptureRequest(Money amount,
                                 @JsonProperty("final_capture") boolean finalCapture,
                                 @JsonProperty("invoice_id") String invoiceId,
                                 @JsonProperty("note_to_payer") String noteToPayer) {}

    public record Capture(String id, String status, Money amount,
                          @JsonProperty("final_capture") Boolean finalCapture,
                          List<Link> links) {
        /** The authorization this capture belongs to, from its "up" link. */
        public String parentAuthorizationId() {
            if (links == null) return null;
            return links.stream().filter(l -> "up".equals(l.rel())).map(Link::href)
                    .map(h -> h.substring(h.lastIndexOf('/') + 1)).findFirst().orElse(null);
        }
    }

    // ---- Payouts v1 ----
    public record PayoutRequest(@JsonProperty("sender_batch_header") SenderBatchHeader senderBatchHeader,
                                List<PayoutItem> items) {}

    public record SenderBatchHeader(@JsonProperty("sender_batch_id") String senderBatchId,
                                    @JsonProperty("email_subject") String emailSubject,
                                    @JsonProperty("email_message") String emailMessage) {}

    /** Payouts v1 uses "currency", not "currency_code" like Orders/Payments v2. */
    public record PayoutAmount(String value, String currency) {}

    public record PayoutItem(@JsonProperty("recipient_type") String recipientType,
                             PayoutAmount amount,
                             String receiver,
                             String note,
                             @JsonProperty("sender_item_id") String senderItemId) {}

    public record PayoutBatch(@JsonProperty("batch_header") BatchHeader batchHeader,
                              List<PayoutItemDetails> items) {
        public PayoutItemDetails firstItem() {
            return items == null || items.isEmpty() ? null : items.getFirst();
        }
    }

    public record BatchHeader(@JsonProperty("payout_batch_id") String payoutBatchId,
                              @JsonProperty("batch_status") String batchStatus) {}

    public record PayoutItemDetails(@JsonProperty("payout_item_id") String payoutItemId,
                                    @JsonProperty("transaction_status") String transactionStatus,
                                    ErrorDetail errors) {}
}
