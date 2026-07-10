package org.example.pulse_ai.ai;

public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}
