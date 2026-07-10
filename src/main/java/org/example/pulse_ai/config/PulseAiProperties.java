package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.ai")
public class PulseAiProperties {

    private String provider = "openai";
    private String model = "gpt-4o";
    private String apiKey = "";
    private String baseUrl = "https://api.proxyapi.ru/openai/v1";
    private int maxRetries = 2;
    private long timeoutSeconds = 60;
    private double temperatureIdeas = 0.7;
    private double temperaturePosts = 0.8;
}
