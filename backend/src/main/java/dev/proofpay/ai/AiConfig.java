package dev.proofpay.ai;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AiConfig {

    @Bean
    ChatModel chatModel(AiProperties p) {
        var b = OpenAiChatModel.builder()
                // Placeholder keeps the app bootable without a key; services call requireEnabled() first.
                .apiKey(p.enabled() ? p.openaiApiKey() : "not-configured")
                .modelName(p.model())
                .timeout(Duration.ofSeconds(p.timeoutSeconds()))
                .maxRetries(2)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .strictJsonSchema(true)
                .strictTools(true);
        if (p.reasoningEffort() != null && !p.reasoningEffort().isBlank()) {
            b.reasoningEffort(p.reasoningEffort());
        }
        return b.build();
    }

    @Bean
    CriteriaCompiler criteriaCompiler(ChatModel model) {
        return AiServices.builder(CriteriaCompiler.class).chatModel(model).build();
    }
}
