package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Подбор фото к постам. Провайдер — Pexels (бесплатный API, коммерческое использование
 * без обязательной атрибуции). Ключ берётся бесплатно на https://www.pexels.com/api/.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.images")
public class PulseImageProperties {

    /** Включить кнопку «Фото к посту». Если ключа нет — фича мягко отключается. */
    private boolean enabled = true;

    /** API-ключ Pexels. Пусто = фича недоступна (бот подскажет, как получить ключ). */
    private String pexelsApiKey = "";

    /** Таймаут HTTP-запроса к провайдеру, мс. */
    private int httpTimeoutMs = 8000;

    /** Сколько кандидатов запрашивать, чтобы «Другое фото» давало разнообразие. */
    private int candidatesPerQuery = 12;

    public boolean isConfigured() {
        return enabled && pexelsApiKey != null && !pexelsApiKey.isBlank();
    }
}
