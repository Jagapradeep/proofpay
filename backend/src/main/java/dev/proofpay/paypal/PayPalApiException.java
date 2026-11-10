package dev.proofpay.paypal;

/** A non-2xx PayPal response, keeping PayPal's error name (e.g. UNPROCESSABLE_ENTITY) and debug id. */
public class PayPalApiException extends RuntimeException {

    private final int status;
    private final String errorName;
    private final String debugId;

    public PayPalApiException(int status, String errorName, String debugId, String body) {
        super("PayPal " + status + " " + errorName + " (debug_id=" + debugId + "): " + body);
        this.status = status;
        this.errorName = errorName;
        this.debugId = debugId;
    }

    public int status() { return status; }
    public String errorName() { return errorName; }
    public String debugId() { return debugId; }
}
