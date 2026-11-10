package dev.proofpay.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Demo-grade API protection:
 * 1. Every /api/** call needs the X-Api-Key header (PayPal webhooks are exempt; they are verified by signature).
 *    The Next.js server adds the key, so it never reaches the browser. Empty key = open (local dev only).
 * 2. The LLM endpoints (criteria, verify) are rate limited per client IP and globally, to protect AI credits.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    public static final String HEADER = "X-Api-Key";

    private static final Pattern AI_ENDPOINT =
            Pattern.compile("^/api/(jobs/[^/]+/criteria|milestones/[^/]+/verify)$");
    static final Duration WINDOW = Duration.ofHours(1);

    private final byte[] key;
    private final int perIpLimit;
    private final int globalLimit;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public ApiKeyFilter(@Value("${proofpay.api-key:}") String key,
                        @Value("${proofpay.ai-rate-limit.per-ip:20}") int perIpLimit,
                        @Value("${proofpay.ai-rate-limit.global:120}") int globalLimit) {
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        this.perIpLimit = perIpLimit;
        this.globalLimit = globalLimit;
        if (this.key.length == 0) log.warn("PROOFPAY_API_KEY is empty: the API is open. Fine locally, never when hosted.");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return !path.startsWith("/api/") || path.startsWith("/api/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (key.length > 0) {
            String given = req.getHeader(HEADER);
            if (given == null || !MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
                reject(res, 401, "Unauthorized", "Missing or wrong " + HEADER);
                return;
            }
        }
        if ("POST".equals(req.getMethod()) && AI_ENDPOINT.matcher(req.getRequestURI()).matches()) {
            String ip = clientIp(req);
            if (!allow("ip:" + ip, perIpLimit) || !allow("global", globalLimit)) {
                reject(res, 429, "Too Many Requests", "AI rate limit reached; try again later");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    /** Sliding one-hour window. Counts only attempts that are allowed. */
    synchronized boolean allow(String bucket, int limit) {
        Instant now = Instant.now();
        Deque<Instant> q = hits.computeIfAbsent(bucket, k -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(WINDOW))) q.pollFirst();
        if (q.size() >= limit) return false;
        q.addLast(now);
        return true;
    }

    /** Behind the Next.js proxy (which holds the key) the first X-Forwarded-For entry is the browser. */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].strip();
        return req.getRemoteAddr();
    }

    private static void reject(HttpServletResponse res, int status, String error, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"status\":" + status + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
