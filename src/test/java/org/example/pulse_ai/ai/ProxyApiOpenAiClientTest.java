package org.example.pulse_ai.ai;

import org.example.pulse_ai.config.PulseAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@ActiveProfiles("test")
class ProxyApiOpenAiClientTest {

    @Autowired
    private PulseAiProperties properties;

    @Autowired
    private LlmClient llmClient;

    @Test
    void contextLoads() {
        assertFalse(properties.getBaseUrl().isBlank());
    }

    // Раскомментируйте для ручной проверки ProxyAPI (нужен application-local.yaml):
    // @Test
    // void completesSimpleRequest() {
    //     LlmResponse response = llmClient.complete(new LlmRequest(
    //             properties.getModel(),
    //             List.of(new LlmMessage("user", "Ответь одним словом: ок")),
    //             50,
    //             0.2,
    //             false
    //     ));
    //     assertFalse(response.content().isBlank());
    // }
}
