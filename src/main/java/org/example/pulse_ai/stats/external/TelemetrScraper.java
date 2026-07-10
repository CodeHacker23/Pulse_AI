package org.example.pulse_ai.stats.external;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TelemetrScraper implements ExternalMetricsProvider {

    private static final int TIMEOUT_MS = 8_000;

    @Override
    public String sourceName() {
        return "Telemetr";
    }

    @Override
    public ExternalChannelMetrics fetch(String username) {
        String name = ExternalScrapeSupport.normalizeUsername(username);
        if (name.isBlank()) {
            return ExternalChannelMetrics.unavailable(sourceName(), "нет username");
        }
        String url = "https://telemetr.me/@" + name;
        try {
            Document doc = ExternalScrapeSupport.fetch(url, TIMEOUT_MS);
            String text = doc.body() != null ? doc.body().text() : doc.text();

            Long subscribers = ExternalScrapeSupport.findNumberNearLabel(text, "подписчик", "аудитория");
            Long avgReach = ExternalScrapeSupport.findNumberNearLabel(text, "средний охват", "охват");
            Double err = ExternalScrapeSupport.extractPercent(text, "ERR", "вовлеч", "err%");

            boolean any = subscribers != null || avgReach != null || err != null;
            if (!any) {
                return ExternalChannelMetrics.unavailable(sourceName(), "страница без метрик или блокировка");
            }
            return new ExternalChannelMetrics(
                    sourceName(),
                    true,
                    subscribers != null ? subscribers.intValue() : null,
                    avgReach != null ? avgReach.intValue() : null,
                    err,
                    null,
                    null,
                    null,
                    null
            );
        } catch (Exception ex) {
            log.warn("Telemetr scrape failed for @{}: {}", name, ex.getMessage());
            return ExternalChannelMetrics.unavailable(sourceName(), "недоступно");
        }
    }
}
