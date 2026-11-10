package dev.proofpay.paypal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paypal")
public record PayPalProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        String platformEmail,
        String webhookId,
        String returnUrl,
        String cancelUrl) {

    public boolean webhookVerificationConfigured() {
        return webhookId != null && !webhookId.isBlank();
    }
}
