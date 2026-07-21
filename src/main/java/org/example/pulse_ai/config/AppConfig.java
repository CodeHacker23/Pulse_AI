package org.example.pulse_ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        TelegramBotProperties.class,
        PulseAiProperties.class,
        PulseAnalysisProperties.class,
        PulseBillingProperties.class,
        PulseExternalProperties.class,
        PulseProductChannelProperties.class,
        PulseImageProperties.class,
        PulseOutreachProperties.class,
        PulseScoutProperties.class
})
public class AppConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
