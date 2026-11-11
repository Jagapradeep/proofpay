package dev.proofpay.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Stores AI answers by a hash of every input that shaped them, so identical requests don't pay twice.
 * Only the LLM's answer is reused: the injection scan, evidence check and policy gate always run again.
 */
@Component
public class AiCache {

    public enum Kind { CRITERIA, VERDICT }

    /** Bump when a prompt or tool schema changes, so old answers stop matching. */
    static final String PROMPT_VERSION = "2026-11-11b";

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public AiCache(JdbcTemplate jdbc, @Value("${proofpay.ai.cache-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public Optional<String> get(String key) {
        if (!enabled) return Optional.empty();
        List<String> rows = jdbc.queryForList("SELECT value::text FROM ai_cache WHERE cache_key = ?", String.class, key);
        if (rows.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE ai_cache SET hits = hits + 1 WHERE cache_key = ?", key);
        return Optional.of(rows.getFirst());
    }

    public void put(String key, Kind kind, String model, String json) {
        if (!enabled) return;
        jdbc.update("INSERT INTO ai_cache (cache_key, kind, model, value) VALUES (?, ?, ?, ?::jsonb) "
                + "ON CONFLICT (cache_key) DO UPDATE SET value = EXCLUDED.value, created_at = now()",
                key, kind.name(), model, json);
    }

    /** sha256 over the parts, each length-prefixed so ("ab","c") and ("a","bc") differ. */
    public static String key(Kind kind, String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            update(md, kind.name());
            update(md, PROMPT_VERSION);
            for (String p : parts) update(md, p == null ? "" : p);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest md, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        md.update((b.length + ":").getBytes(StandardCharsets.UTF_8));
        md.update(b);
    }
}
