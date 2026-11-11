package dev.proofpay.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {

    private static MockHttpServletResponse run(ApiKeyFilter f, String method, String path, String key, String ip)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (key != null) req.addHeader(ApiKeyFilter.HEADER, key);
        if (ip != null) req.addHeader("X-Forwarded-For", ip + ", 10.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void requiresTheKeyOnApiButNotOnWebhooksOrHealth() throws Exception {
        ApiKeyFilter f = new ApiKeyFilter("s3cret", 20, 100);
        assertThat(run(f, "GET", "/api/jobs", null, null).getStatus()).isEqualTo(401);
        assertThat(run(f, "GET", "/api/jobs", "wrong", null).getStatus()).isEqualTo(401);
        assertThat(run(f, "GET", "/api/jobs", "s3cret", null).getStatus()).isEqualTo(200);
        assertThat(run(f, "POST", "/api/webhooks/paypal", null, null).getStatus()).isEqualTo(200);
        assertThat(run(f, "GET", "/actuator/health", null, null).getStatus()).isEqualTo(200);
    }

    @Test
    void oddSpellingsOfAPathStillNeedTheKey() throws Exception {
        ApiKeyFilter f = new ApiKeyFilter("s3cret", 20, 100);
        for (String path : new String[] {"/api;x/jobs", "/api/webhooks/../jobs", "/api/webhooks/paypal;x/../../jobs",
                "/api/webhooks/other", "//api/jobs", "/actuator/env", "/actuator/health/../env"}) {
            assertThat(run(f, "GET", path, null, null).getStatus()).as(path).isEqualTo(401);
        }
    }

    @Test
    void pathParametersDoNotDodgeTheRateLimit() throws Exception {
        ApiKeyFilter f = new ApiKeyFilter("k", 1, 10);
        assertThat(run(f, "POST", "/api/milestones/abc/verify", "k", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(run(f, "POST", "/api/milestones/abc/verify;x=1", "k", "1.1.1.1").getStatus()).isEqualTo(429);
        assertThat(run(f, "POST", "/api/milestones/abc;y/verify", "k", "1.1.1.1").getStatus()).isEqualTo(429);
    }

    @Test
    void emptyKeyMeansOpenForLocalDev() throws Exception {
        assertThat(run(new ApiKeyFilter("", 20, 100), "GET", "/api/jobs", null, null).getStatus()).isEqualTo(200);
    }

    @Test
    void rateLimitsAiEndpointsPerIpAndGlobally() throws Exception {
        ApiKeyFilter f = new ApiKeyFilter("k", 2, 3);
        String verify = "/api/milestones/abc/verify";
        assertThat(run(f, "POST", verify, "k", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(run(f, "POST", "/api/jobs/x/criteria", "k", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(run(f, "POST", verify, "k", "1.1.1.1").getStatus()).isEqualTo(429);   // per-IP limit
        assertThat(run(f, "POST", verify, "k", "2.2.2.2").getStatus()).isEqualTo(200);   // other IP fine
        assertThat(run(f, "POST", verify, "k", "3.3.3.3").getStatus()).isEqualTo(429);   // global limit
        assertThat(run(f, "GET", "/api/jobs", "k", "1.1.1.1").getStatus()).isEqualTo(200); // non-AI unaffected
    }
}
