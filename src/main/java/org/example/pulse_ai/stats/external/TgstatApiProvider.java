package org.example.pulse_ai.stats.external;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Провайдер внешних метрик поверх официального TGStat API.
 * Реальные подписчики, охват, ERR и индекс цитирования — то, чего нет в скрейпинге.
 */
@Component
@RequiredArgsConstructor
public class TgstatApiProvider implements ExternalMetricsProvider {

    private final TgstatApiClient client;

    @Override
    public String sourceName() {
        return "TGStat";
    }

    @Override
    public ExternalChannelMetrics fetch(String username) {
        return client.getStat(username)
                .orElseGet(() -> ExternalChannelMetrics.unavailable(sourceName(), "нет данных"));
    }
}
