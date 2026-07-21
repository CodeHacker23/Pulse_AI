package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class HardAuditService {

    private static final String SYSTEM = """
            Ты — жёсткий консультант Telegram-каналов. Без комплиментов и «молодец».
            Говоришь правду: где канал теряет деньги, охват и доверие.
            По-русски, коротко. **Жирным** — главное. Ровно 3 дыры и 3 действия.
            Не выдумывай цифры — только из данных.""";

    private final LlmService llmService;
    private final ChannelDeepAnalysisService deepAnalysisService;
    private final PulseAnalysisProperties analysisProperties;

    public String audit(
            Long channelId,
            String channelTitle,
            AnalysisMetrics metrics,
            int subscribers,
            LocalDate periodFrom,
            LocalDate periodTo
    ) {
        if (deepAnalysisService.needsSparseAnalysis(metrics, subscribers)) {
            return sparseHardAudit(channelTitle, metrics);
        }

        String prompt = """
                Жёсткий аудит канала «%s». Без сахара.

                Данные: %d постов, средние просмотры %d, ER %s%%, частота: %s.

                Топ посты:
                %s

                Слабые:
                %s

                Формат:

                🔥 **Главная дыра** — одна самая болезненная проблема (1–2 предложения)

                📉 **3 дыры** — нумерованный список, каждая строка = факт + ущерб

                ⚡ **3 действия на эту неделю** — конкретно, без «будьте активнее»

                💸 **Где теряются деньги/рост** — 1 абзац, если канал монетизируется или может
                """.formatted(
                channelTitle,
                metrics.postCount(),
                metrics.avgViews(),
                metrics.avgEngagementRate(),
                metrics.frequencyRecommendation(),
                formatPosts(metrics.topPosts()),
                formatPosts(metrics.worstPosts())
        );

        try {
            return llmService.completeTextWithTimeout(
                    SYSTEM, prompt, analysisProperties.getLlmTimeoutSeconds(), 2500);
        } catch (Exception ex) {
            log.warn("Hard audit LLM failed: {}", ex.getMessage());
            return sparseHardAudit(channelTitle, metrics);
        }
    }

    private static String formatPosts(java.util.List<org.example.pulse_ai.stats.model.PostMetric> posts) {
        if (posts == null || posts.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (var p : posts) {
            sb.append("• ").append(p.title()).append(" — ").append(p.views()).append(" просм.\n");
        }
        return sb.toString().trim();
    }

    private static String sparseHardAudit(String channelTitle, AnalysisMetrics metrics) {
        return """
                🔥 **Главная дыра**
                «%s» почти не вещает: %d пост(ов). Без ленты канал не существует в голове подписчика.

                📉 **3 дыры**
                1. **Нет регулярности** — подписчики забывают, зачем подписались
                2. **Нет «крючка» в закрепе** — новый человек не понимает ценность
                3. **Нет обратной связи** — не видно, что резонирует

                ⚡ **3 действия на эту неделю**
                1. **3 поста за 7 дней** — минимум для сигнала «канал жив»
                2. **Закреп с оффером** — зачем оставаться
                3. **Опрос в конце поста** — первые реакции

                💸 **Где теряются деньги/рост**
                Пока нет контента — не продаётся ни реклама, ни доверие. Сначала лента, потом монетизация."""
                .formatted(channelTitle, metrics.postCount()).trim();
    }
}
