package org.example.pulse_ai.stats.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ExternalMetricsService {

    private static final int PROVIDER_TIMEOUT_SECONDS = 10;

    private final List<ExternalMetricsProvider> providers;

    public ExternalMetricsService(List<ExternalMetricsProvider> providers) {
        this.providers = providers;
    }

    public List<ExternalChannelMetrics> collect(String username) {
        if (username == null || username.isBlank() || providers.isEmpty()) {
            return List.of();
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(providers.size(), 4));
        try {
            List<Future<ExternalChannelMetrics>> futures = new ArrayList<>();
            for (ExternalMetricsProvider provider : providers) {
                Callable<ExternalChannelMetrics> task = () -> provider.fetch(username);
                futures.add(pool.submit(task));
            }

            List<ExternalChannelMetrics> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ExternalMetricsProvider provider = providers.get(i);
                try {
                    results.add(futures.get(i).get(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (Exception ex) {
                    log.warn("Провайдер {} не ответил: {}", provider.sourceName(), ex.getMessage());
                    results.add(ExternalChannelMetrics.unavailable(provider.sourceName(), "таймаут"));
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Builds a summary of ONLY the external platforms that returned data.
     * Returns {@code null} if nothing is available (so the bot shows nothing instead of "нет данных").
     */
    public String buildSummary(String username) {
        List<ExternalChannelMetrics> metrics = collect(username);
        StringBuilder sb = new StringBuilder("📡 <b>Данные с внешних площадок</b>\n");
        boolean anyAvailable = false;
        for (ExternalChannelMetrics m : metrics) {
            if (!m.available() || !hasAnyValue(m)) {
                continue;
            }
            anyAvailable = true;
            sb.append("\n▪️ <b>").append(m.source()).append("</b>\n");
            if (m.subscribers() != null) {
                sb.append("   👥 Подписчики: ").append(formatNumber(m.subscribers())).append('\n');
            }
            if (m.avgReach() != null) {
                sb.append("   👁 Средний охват: ").append(formatNumber(m.avgReach())).append('\n');
            }
            if (m.err() != null) {
                sb.append("   📊 ERR: ").append(m.err()).append("%\n");
            }
            if (m.citationIndex() != null) {
                sb.append("   🔗 Индекс цитирования: ").append(m.citationIndex()).append('\n');
            }
            if (m.adPriceRub() != null) {
                sb.append("   💰 Цена рекламы: ~").append(formatNumber(m.adPriceRub().intValue())).append(" ₽\n");
            }
        }
        return anyAvailable ? sb.toString().trim() : null;
    }

    /**
     * Возвращает лучший доступный набор метрик (приоритет — TGStat API),
     * чтобы уточнить основную статистику без упоминания источника.
     * {@code null}, если данных нет.
     */
    public ExternalChannelMetrics bestMetrics(String username) {
        if (username == null || username.isBlank()) {
            return ExternalChannelMetrics.unavailable("none", "нет username");
        }
        ExternalChannelMetrics best = null;
        for (ExternalChannelMetrics m : collect(username)) {
            if (!m.available() || !hasAnyValue(m)) {
                continue;
            }
            // Telega часто врёт по микроканалам — не берём как единственный источник подписчиков.
            if ("Telega.in".equals(m.source()) && m.subscribers() != null && m.subscribers() > 1000
                    && (m.avgReach() == null || m.err() == null)) {
                continue;
            }
            if ("TGStat".equals(m.source())) {
                return m;
            }
            if (best == null) {
                best = m;
            }
        }
        return best != null ? best : ExternalChannelMetrics.unavailable("none", "нет данных");
    }

    /**
     * Компактное описание проверенных метрик для промпта LLM (внутреннее, не показывается юзеру).
     * {@code null}, если данных нет.
     */
    public String describeForLlm(ExternalChannelMetrics m) {
        if (m == null || !m.available() || !hasAnyValue(m)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(
                "Проверенные метрики канала (внешний источник, для точности — НЕ упоминай источник в ответе):\n");
        if (m.subscribers() != null) {
            sb.append("- подписчиков: ").append(m.subscribers()).append('\n');
        }
        if (m.avgReach() != null) {
            sb.append("- средний охват поста: ").append(m.avgReach()).append('\n');
        }
        if (m.err() != null) {
            sb.append("- ERR (реальная вовлечённость): ").append(m.err()).append("%\n");
        }
        if (m.citationIndex() != null) {
            sb.append("- индекс цитирования: ").append(m.citationIndex()).append('\n');
        }
        return sb.toString().trim();
    }

    private static boolean hasAnyValue(ExternalChannelMetrics m) {
        return m.subscribers() != null || m.avgReach() != null || m.err() != null
                || m.citationIndex() != null || m.adPriceRub() != null;
    }

    private static String formatNumber(int value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format("%.1fk", value / 1_000.0);
        }
        return String.valueOf(value);
    }
}
