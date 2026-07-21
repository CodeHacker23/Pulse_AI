package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.stats.AnalyticsService;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Перк «📬 Еженедельный дайджест»: сравнение последних 7 дней с предыдущими 7 днями,
 * что выросло / просело, одна идея на неделю. Retention-механика.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyDigestService {

    private static final String SYSTEM = """
            Ты — контент-стратег Telegram. Пишешь короткий еженедельный дайджест владельцу канала.
            Только реальные данные, без выдуманных цифр. По-русски, тепло, но по делу.
            **Жирным** — главное, _курсивом_ — названия постов. Максимум 900 символов.
            Тон: «бот помнит про твой канал и следит за динамикой».""";

    private final LlmService llmService;
    private final AnalyticsService analyticsService;
    private final PulseAnalysisProperties analysisProperties;

    public String buildDigest(ChannelEntity channel) {
        LocalDate today = LocalDate.now();
        LocalDate thisWeekFrom = today.minusDays(6);
        LocalDate prevWeekTo = thisWeekFrom.minusDays(1);
        LocalDate prevWeekFrom = prevWeekTo.minusDays(6);

        AnalysisMetrics thisWeek = analyticsService.analyze(channel.getId(), thisWeekFrom, today);
        AnalysisMetrics prevWeek = analyticsService.analyze(channel.getId(), prevWeekFrom, prevWeekTo);

        if (thisWeek.postCount() == 0 && prevWeek.postCount() == 0) {
            return sparseDigest(channel.getTitle());
        }

        String prompt = buildPrompt(channel, thisWeek, prevWeek);
        try {
            String text = llmService.completeTextWithTimeout(
                    SYSTEM, prompt, analysisProperties.getLlmTimeoutSeconds(), 1400);
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        } catch (Exception ex) {
            log.warn("Weekly digest LLM failed for channel {}: {}", channel.getId(), ex.getMessage());
        }
        return fallback(channel, thisWeek, prevWeek);
    }

    private String buildPrompt(ChannelEntity channel, AnalysisMetrics now, AnalysisMetrics prev) {
        return """
                Канал: «%s» (%d подписчиков)

                Последние 7 дней: %d постов, средние просмотры %d, ER %s%%
                Предыдущие 7 дней: %d постов, средние просмотры %d, ER %s%%

                Топ-посты недели:
                %s

                Формат ответа (используй **двойные звёздочки** для жирного, НЕ HTML-теги):

                📬 **Дайджест недели**

                📈 **Что выросло / просело** — сравни недели по просмотрам и постам (реальные числа)

                🏆 **Пост недели** — назови лучший и почему сработал (психология восприятия)

                💡 **Одна идея на следующую неделю** — конкретная, под этот канал
                """.formatted(
                channel.getTitle(),
                channel.getSubscriberCount() != null ? channel.getSubscriberCount() : 0,
                now.postCount(), now.avgViews(), now.avgEngagementRate(),
                prev.postCount(), prev.avgViews(), prev.avgEngagementRate(),
                formatPosts(now.topPosts())
        );
    }

    private static String fallback(ChannelEntity channel, AnalysisMetrics now, AnalysisMetrics prev) {
        int deltaViews = now.avgViews() - prev.avgViews();
        String trend = deltaViews > 0 ? "📈 просмотры выросли" : deltaViews < 0 ? "📉 просмотры просели" : "➡️ без изменений";
        return """
                📬 **Дайджест недели — %s**

                📊 За 7 дней: %d постов, средние просмотры %d (%s против прошлой недели).

                💡 **Идея на неделю:** повторите формат лучшего поста и добавьте вопрос-крючок в первую строку — это поднимает вовлечённость."""
                .formatted(
                        channel.getTitle(),
                        now.postCount(), now.avgViews(), trend)
                .trim();
    }

    private static String sparseDigest(String title) {
        return """
                📬 **Дайджест недели — %s**

                За две недели постов почти не было. Канал «замолкает» — подписчики забывают, зачем подписались.

                💡 **Минимум на неделю:** 3 поста + 1 опрос. Даже короткие апдейты держат канал живым."""
                .formatted(title).trim();
    }

    private static String formatPosts(List<PostMetric> posts) {
        if (posts == null || posts.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (PostMetric p : posts.stream().limit(3).toList()) {
            sb.append("  • ").append(p.title()).append(" — ").append(p.views()).append(" просм.\n");
        }
        return sb.toString().trim();
    }
}
