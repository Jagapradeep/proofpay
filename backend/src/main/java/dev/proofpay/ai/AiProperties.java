package dev.proofpay.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proofpay.ai")
public record AiProperties(
        String openaiApiKey,
        String model,
        String reasoningEffort,
        int timeoutSeconds,
        String githubToken) {

    public boolean enabled() {
        return openaiApiKey != null && !openaiApiKey.isBlank();
    }

    public void requireEnabled() {
        if (!enabled()) throw new AiUnavailableException("OPENAI_API_KEY is not set in .env");
    }
}
