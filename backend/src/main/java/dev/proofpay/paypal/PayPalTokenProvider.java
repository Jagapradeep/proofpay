package dev.proofpay.paypal;

import dev.proofpay.paypal.PayPalDtos.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

/** Client-credentials token, cached until 5 minutes before it expires (~9h lifetime). */
@Component
public class PayPalTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(PayPalTokenProvider.class);
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final RestClient http;
    private final PayPalProperties props;

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public PayPalTokenProvider(RestClient.Builder builder, PayPalProperties props) {
        this.props = props;
        this.http = builder.baseUrl(props.baseUrl()).build();
    }

    public synchronized String token() {
        if (token == null || Instant.now().isAfter(expiresAt.minus(REFRESH_MARGIN))) {
            refresh();
        }
        return token;
    }

    /** Called when PayPal answers 401 so the next call fetches a fresh token. */
    public synchronized void invalidate() {
        token = null;
    }

    private void refresh() {
        if (props.clientId() == null || props.clientId().isBlank()) {
            throw new IllegalStateException("PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET are not set");
        }
        TokenResponse res = http.post()
                .uri("/v1/oauth2/token")
                .headers(h -> h.setBasicAuth(props.clientId(), props.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(TokenResponse.class);
        token = res.accessToken();
        expiresAt = Instant.now().plusSeconds(res.expiresIn());
        log.info("PayPal token refreshed, valid for {}s", res.expiresIn());
    }
}
