package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.scout")
public class PulseScoutProperties {

    private boolean enabled = false;

    /** URL Python/TDLib sidecar, напр. http://127.0.0.1:8090 */
    private String sidecarUrl = "";

    private int sidecarTimeoutMs = 30_000;

    /** Telegram user id админов (статус scout, алерты). */
    private List<Long> adminTelegramIds = new ArrayList<>();

    /** Ключевые слова Ad Radar observer. */
    private List<String> radarKeywords = List.of(
            "реклам", "прайс", "размещени", "price", "ad slot", "партнёр");

    /** Сброс sent_today — cron в Europe/Moscow 00:05 */
    private boolean dailyCounterResetEnabled = true;

    public boolean sidecarConfigured() {
        return sidecarUrl != null && !sidecarUrl.isBlank();
    }

    public boolean isAdmin(Long telegramId) {
        return telegramId != null && adminTelegramIds.contains(telegramId);
    }
}
