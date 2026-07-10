package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.billing")
public class PulseBillingProperties {

    /** false = всё бесплатно (режим разработки) */
    private boolean enabled = false;
}
