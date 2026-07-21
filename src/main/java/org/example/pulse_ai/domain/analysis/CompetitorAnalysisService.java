package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.domain.channel.ChannelService;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.stats.AnalyticsService;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Перк «⚔️ Анализ конкурента»: сравнивает канал пользователя с одним похожим каналом.
 * Оба канала анализируются по реальным данным, LLM объясняет, где вы сильнее/слабее.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompetitorAnalysisService {

    private static final String SYSTEM = """
            Ты — контент-стратег Telegram и специалист по потребительской психологии.
            Сравниваешь два канала по реальным данным. Без воды и без выдуманных цифр.
            По-русски, коротко, пунктами. **Жирным** — главное, _курсивом_ — названия постов.
            Задача: показать владельцу первого канала, где он сильнее конкурента,
            где отстаёт, и что перенять. Ровно 3 вывода-действия в конце.""";

    private final LlmService llmService;
    private final AnalyticsService analyticsService;
    private final ChannelService channelService;
    private final PulseAnalysisProperties analysisProperties;

    public CompetitorResult compare(ChannelEntity myChannel, String competitorInput) {
        ChannelEntity competitor;
        try {
            competitor = channelService.resolveForComparison(competitorInput);
        } catch (Exception ex) {
            return CompetitorResult.failure(ex.getMessage());
        }
        if (competitor.getTelegramChatId() != null
                && competitor.getTelegramChatId().equals(myChannel.getTelegramChatId())) {
            return CompetitorResult.failure("Это ваш же канал. Пришлите канал конкурента.");
        }

        LocalDate periodTo = LocalDate.now();
        LocalDate periodFrom = periodTo.minusDays(analysisProperties.getPeriodDays() - 1L);

        AnalysisMetrics mine = analyticsService.analyze(myChannel.getId(), periodFrom, periodTo);
        AnalysisMetrics theirs = analyticsService.analyze(competitor.getId(), periodFrom, periodTo);

        String prompt = buildPrompt(myChannel, mine, competitor, theirs);
        String comparison;
        try {
            comparison = llmService.completeTextWithTimeout(
                    SYSTEM, prompt, analysisProperties.getLlmTimeoutSeconds(), 2200);
        } catch (Exception ex) {
            log.warn("Competitor LLM failed: {}", ex.getMessage());
            comparison = fallback(myChannel, mine, competitor, theirs);
        }
        if (comparison == null || comparison.isBlank()) {
            comparison = fallback(myChannel, mine, competitor, theirs);
        }
        return CompetitorResult.success(competitor.getTitle(), comparison.trim());
    }

    private String buildPrompt(
            ChannelEntity my, AnalysisMetrics mine,
            ChannelEntity comp, AnalysisMetrics theirs
    ) {
        return """
                Канал А (пользователь): «%s»
                • подписчиков: %d
                • постов: %d, средние просмотры: %d, ER: %s%%
                • топ-посты:
                %s

                Канал Б (конкурент): «%s»
                • подписчиков: %d
                • постов: %d, средние просмотры: %d, ER: %s%%
                • топ-посты:
                %s

                Формат ответа:

                📊 **Кто сильнее в цифрах** — 2–3 строки сравнения охвата и вовлечённости (только реальные числа)

                ✅ **Где канал А выигрывает** — 2 пункта

                ⚠️ **Где канал А отстаёт** — 2 пункта, честно

                🎯 **3 действия перенять у конкурента** — конкретно, применимо на этой неделе
                """.formatted(
                my.getTitle(),
                nz(my.getSubscriberCount()),
                mine.postCount(), mine.avgViews(), mine.avgEngagementRate(),
                formatPosts(mine.topPosts()),
                comp.getTitle(),
                nz(comp.getSubscriberCount()),
                theirs.postCount(), theirs.avgViews(), theirs.avgEngagementRate(),
                formatPosts(theirs.topPosts())
        );
    }

    private static String fallback(
            ChannelEntity my, AnalysisMetrics mine,
            ChannelEntity comp, AnalysisMetrics theirs
    ) {
        String leaderViews = mine.avgViews() >= theirs.avgViews() ? my.getTitle() : comp.getTitle();
        return """
                📊 **Сравнение в цифрах**
                «%s»: %d просм., ER %s%% (%d постов)
                «%s»: %d просм., ER %s%% (%d постов)
                По средним просмотрам впереди: **%s**

                🎯 **3 действия**
                1. **Повторите формат топ-поста конкурента** — адаптируйте под свой стиль
                2. **Держите частоту** — регулярность важнее разовых всплесков
                3. **Тестируйте крючок в первой строке** — сравните свои заходы с сильными у конкурента"""
                .formatted(
                        my.getTitle(), mine.avgViews(), mine.avgEngagementRate(), mine.postCount(),
                        comp.getTitle(), theirs.avgViews(), theirs.avgEngagementRate(), theirs.postCount(),
                        leaderViews)
                .trim();
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
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

    public record CompetitorResult(boolean success, String competitorTitle, String text, String error) {
        public static CompetitorResult success(String title, String text) {
            return new CompetitorResult(true, title, text, null);
        }

        public static CompetitorResult failure(String error) {
            return new CompetitorResult(false, null, null, error);
        }
    }
}
