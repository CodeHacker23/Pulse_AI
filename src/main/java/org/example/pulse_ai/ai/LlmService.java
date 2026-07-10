package org.example.pulse_ai.ai;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseAiProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
public class LlmService {

    private final LlmClient llmClient;
    private final PulseAiProperties properties;

    public String completeJson(String systemPrompt, String userPrompt) {
        return completeJsonWithTimeout(systemPrompt, userPrompt, (int) properties.getTimeoutSeconds());
    }

    public String completeJsonWithTimeout(String systemPrompt, String userPrompt, int timeoutSeconds) {
        return completeWithTimeout(systemPrompt, userPrompt, timeoutSeconds, 2000, true);
    }

    public String completeTextWithTimeout(String systemPrompt, String userPrompt, int timeoutSeconds, int maxTokens) {
        return completeWithTimeout(systemPrompt, userPrompt, timeoutSeconds, maxTokens, false);
    }

    private String completeWithTimeout(
            String systemPrompt,
            String userPrompt,
            int timeoutSeconds,
            int maxTokens,
            boolean jsonResponse
    ) {
        LlmRequest request = new LlmRequest(
                properties.getModel(),
                List.of(
                        new LlmMessage("system", systemPrompt),
                        new LlmMessage("user", userPrompt)
                ),
                maxTokens,
                properties.getTemperatureIdeas(),
                jsonResponse
        );
        try {
            return CompletableFuture
                    .supplyAsync(() -> llmClient.complete(request).content())
                    .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("LLM timeout after " + timeoutSeconds + "s", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("LLM call failed: " + ex.getMessage(), ex);
        }
    }
}
