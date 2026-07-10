package org.example.pulse_ai.ai;

import java.util.List;

public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        int maxTokens,
        double temperature,
        boolean jsonResponse
) {
}
