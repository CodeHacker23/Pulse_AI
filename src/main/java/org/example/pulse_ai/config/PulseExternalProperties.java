package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.external")
public class PulseExternalProperties {

    /** Персональный токен TGStat API (https://api.tgstat.ru). Пусто — интеграция выключена. */
    private String tgstatToken = "";
    private int tgstatTimeoutMs = 8_000;
    /**
     * true = TGStat только для CONTENT+ (и при billing.enabled).
     * false = любой запрос с валидным token (дороже по квоте API).
     */
    private boolean tgstatPaidOnly = true;

    public boolean isTgstatEnabled() {
        return tgstatToken != null && !tgstatToken.isBlank();
    }
}
