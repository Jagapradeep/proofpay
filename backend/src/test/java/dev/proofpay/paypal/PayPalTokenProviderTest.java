package dev.proofpay.paypal;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PayPalTokenProviderTest {

    private static final String BASE = "https://api-m.sandbox.paypal.com";

    private static PayPalProperties props(String clientId) {
        return new PayPalProperties(BASE, clientId, "secret", "p@x.com", "", "r", "c");
    }

    @Test
    void fetchesOnceThenCachesUntilInvalidated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String tokenJson = "{\"access_token\":\"%s\",\"expires_in\":32400}";
        server.expect(requestTo(BASE + "/v1/oauth2/token"))
                .andExpect(header("Authorization", "Basic aWQ6c2VjcmV0")) // base64("id:secret")
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(tokenJson.formatted("A"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/oauth2/token"))
                .andRespond(withSuccess(tokenJson.formatted("B"), MediaType.APPLICATION_JSON));

        var provider = new PayPalTokenProvider(builder, props("id"));

        assertThat(provider.token()).isEqualTo("A");
        assertThat(provider.token()).isEqualTo("A"); // cached: no second HTTP call
        provider.invalidate();
        assertThat(provider.token()).isEqualTo("B");
        server.verify();
    }

    @Test
    void refreshesWhenTokenIsAboutToExpire() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 60s lifetime is inside the 5 minute refresh margin, so every call refreshes
        server.expect(requestTo(BASE + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"A\",\"expires_in\":60}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"B\",\"expires_in\":32400}", MediaType.APPLICATION_JSON));

        var provider = new PayPalTokenProvider(builder, props("id"));

        assertThat(provider.token()).isEqualTo("A");
        assertThat(provider.token()).isEqualTo("B");
        server.verify();
    }

    @Test
    void missingCredentialsGiveAClearError() {
        var provider = new PayPalTokenProvider(RestClient.builder(), props(""));
        assertThatThrownBy(provider::token).hasMessageContaining("PAYPAL_CLIENT_ID");
    }
}
