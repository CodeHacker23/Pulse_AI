package org.example.pulse_ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.pulse_ai.domain.scout.NoOpScoutSessionGateway;
import org.example.pulse_ai.domain.scout.RestScoutSessionGateway;
import org.example.pulse_ai.domain.scout.ScoutSessionGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScoutGatewayConfig {

    @Bean
    ScoutSessionGateway scoutSessionGateway(PulseScoutProperties properties, ObjectMapper objectMapper) {
        if (properties.sidecarConfigured()) {
            return new RestScoutSessionGateway(properties, objectMapper);
        }
        return new NoOpScoutSessionGateway();
    }
}
