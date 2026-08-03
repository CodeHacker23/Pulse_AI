package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.admin")
public class PulseAdminProperties {

    /** Shared secret for /admin web UI (query ?token= or header X-Admin-Token). */
    private String webToken = "pulse-local-admin";

    private boolean webEnabled = true;
}
