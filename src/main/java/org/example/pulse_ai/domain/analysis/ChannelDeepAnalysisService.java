package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.domain.audience.AudienceBrief;
import org.example.pulse_ai.domain.audience.AudienceIntelService;
import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.example.pulse_ai.stats.AnalyticsService;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelDeepAnalysisService {

    private static final String SYSTEM_PROMPT = """
            Ты — сильный контент-стратег Telegram-каналов и специалист по потребительской психологии
            (нейромаркетинг, поведенческая экономика). Ты понимаешь, как аудитория принимает решения:
            триггеры внимания в первых 2 строках, социальное доказательство, дефицит, петли любопытства,
            эффект незавершённости, боль → выгода → действие.

            Как писать:
            - По-русски, живо и уверенно, как опытный человек, а не как ИИ. Никакой воды и общих фраз.
            - Каждый тезис — конкретный и применимый сегодня. Ссылайся на реальные посты и цифры из данных.
            - Ключевые мысли выделяй **жирным** (двойные звёздочки), нюансы и термины — _курсивом_
              (нижнее подчёркивание). Коротко, пунктами, не абзацами-простынями. Названия постов бери в _курсив_.
            - Объясняй ПОЧЕМУ работает или не работает — через психологию восприятия, а не «нравится/не нравится».
            - Не выдумывай цифры. Если данных мало — так и скажи и дай гипотезу.
            - Рекомендаций РОВНО 3 (мозгу тяжело выбирать из многих) — самые сильные, с ожидаемым эффектом.""";

    private final LlmService llmService;
    private final AnalyticsService analyticsService;
    private final PulseAnalysisProperties analysisProperties;
    private final AudienceIntelService audienceIntelService;

    public String analyzeChannel(
            Long channelId,
            String channelTitle,
            AnalysisMetrics metrics,
            LocalDate periodFrom,
            LocalDate periodTo
    ) {
        return analyzeChannel(channelId, channelTitle, metrics, periodFrom, periodTo, null, null);
    }

    public String analyzeChannel(
            Long channelId,
            String channelTitle,
            AnalysisMetrics metrics,
            LocalDate periodFrom,
            LocalDate periodTo,
            String externalSummary
    ) {
        return analyzeChannel(channelId, channelTitle, metrics, periodFrom, periodTo, externalSummary, null);
    }

    public String analyzeChannel(
            Long channelId,
            String channelTitle,
            AnalysisMetrics metrics,
            LocalDate periodFrom,
            LocalDate periodTo,
            String externalSummary,
            AudienceBrief audienceBrief
    ) {
        try {
            List<ChannelPostEntity> posts = analyticsService.loadPosts(channelId, periodFrom, periodTo);
            String prompt = buildPrompt(channelTitle, metrics, posts, externalSummary, audienceBrief);
            return llmService.completeTextWithTimeout(
                    SYSTEM_PROMPT,
                    prompt,
                    analysisProperties.getLlmTimeoutSeconds(),
                    3500
            );
        } catch (Exception ex) {
            log.warn("Deep LLM analysis failed, using fallback: {}", ex.getMessage());
            return fallbackAnalysis(channelTitle, metrics);
        }
    }

    public boolean needsSparseAnalysis(AnalysisMetrics metrics, int subscribers) {
        if (metrics.postCount() < analysisProperties.getMinPostsFull() || metrics.limitedAnalysis()) {
            return true;
        }
        if (subscribers > 0 && subscribers <= 100
                && metrics.avgViews() > Math.max(subscribers * 3, 30)) {
            return true;
        }
        return false;
    }

    /**
     * Честный разбор для нового/пустого канала — без LLM и без чужих метрик с TGStat.
     */
    public String sparseChannelAnalysis(String channelTitle, AnalysisMetrics metrics, int subscribers) {
        String subsLine = subscribers > 0
                ? "**" + subscribers + "** подписчиков"
                : "подписчиков пока мало";
        String postsLine = metrics.postCount() == 1
                ? "**1** пост"
                : "**" + metrics.postCount() + "** постов";

        return """
                📌 **Главное**
                Канал «%s» только на старте: %s, %s. Сейчас рано судить об охватах и вовлечённости — сначала нужна регулярная лента из 5–10 постов.

                🎯 **О чём канал и кто аудитория**
                Пока мало сигналов из контента. Определите одну тему и для кого вы пишете — это станет основой первых постов.

                🧲 **Почему цепляет (или нет)**
                Недостаточно публикаций, чтобы увидеть паттерны. Первые посты — тест: заголовок с выгодой, короткий текст, один чёткий вывод.

                📉 **Где теряются просмотры**
                На пустом канале главная «просадка» — отсутствие контента. Подписчики не возвращаются, если в ленте тишина.

                💡 **3 шага роста**
                1. **Опубликуйте 3 поста за неделю** (знакомство, польза, личная история)
                2. **Закрепите пост с оффером канала** (зачем подписываться)
                3. **Спросите аудиторию вопрос в конце** (первые реакции и обратная связь)
                """.formatted(channelTitle, postsLine, subsLine).trim();
    }

    private String buildPrompt(
            String channelTitle,
            AnalysisMetrics metrics,
            List<ChannelPostEntity> posts,
            String externalSummary,
            AudienceBrief audienceBrief
    ) {
        StringBuilder topPosts = new StringBuilder();
        for (PostMetric post : metrics.topPosts()) {
            topPosts.append("• ").append(post.title())
                    .append(" — ").append(post.views()).append(" просм., ER ")
                    .append(post.engagementRate()).append("%\n");
        }

        StringBuilder weakPosts = new StringBuilder();
        for (PostMetric post : metrics.worstPosts()) {
            weakPosts.append("• ").append(post.title())
                    .append(" — ").append(post.views()).append(" просм.");
            if (post.failureReason() != null) {
                weakPosts.append(" (").append(post.failureReason()).append(')');
            }
            weakPosts.append('\n');
        }

        StringBuilder samples = new StringBuilder();
        Set<Long> usedIds = new LinkedHashSet<>();
        for (PostMetric metric : metrics.topPosts()) {
            posts.stream()
                    .filter(p -> p.getTelegramMessageId().equals(metric.messageId()))
                    .findFirst()
                    .ifPresent(p -> appendSample(samples, usedIds, p));
        }
        for (PostMetric metric : metrics.worstPosts()) {
            posts.stream()
                    .filter(p -> p.getTelegramMessageId().equals(metric.messageId()))
                    .findFirst()
                    .ifPresent(p -> appendSample(samples, usedIds, p));
        }
        posts.stream()
                .sorted(Comparator.comparing(ChannelPostEntity::getPublishedAt).reversed())
                .limit(analysisProperties.getLlmSamplePosts())
                .forEach(p -> appendSample(samples, usedIds, p));

        String externalBlock = (externalSummary != null && !externalSummary.isBlank())
                ? "\n\n🌐 Данные с внешних площадок (TGStat/Telemetr/Telega.in):\n" + externalSummary
                : "";
        String audienceBlock = audienceBrief != null
                ? "\n\n" + audienceIntelService.promptBlock(audienceBrief)
                : "";

        return """
                Сделай полный разбор Telegram-канала «%s» за период.

                📊 Метрики канала:
                - Постов: %d
                - Средние просмотры: %d (%s%% к прошлому периоду)
                - Средний ER: %s%%
                - Лучшее время публикации: %s
                - Рекомендуемая частота: %s

                🔥 Топ посты:
                %s

                📉 Слабые посты:
                %s

                📝 Фрагменты постов для анализа стиля:
                %s%s%s

                ВАЖНО про рекламу: посты с меткой [РЕПОСТ ИЗ ДРУГОГО КАНАЛА] или [ВОЗМОЖНО РЕКЛАМА],
                а также те, что по смыслу НЕ относятся к тематике канала (чужой продукт, посев, реф-ссылки) — это НЕ контент канала.
                Не критикуй их как «слабые посты» и не считай их провалом автора. Отдели их: оцени лишь
                примерную долю рекламы и как она влияет на охваты. Тематику канала определяй по основному контенту.

                Напиши отчёт строго по этой структуре (эмодзи-заголовки, коротко, по делу, БЕЗ воды):

                📌 **Главное** — 2-3 предложения: сильная сторона канала и главная точка роста.

                🎯 **О чём канал и кто аудитория** — роль читателя (профессия / жизненная ситуация), не ярлык каталога.
                Запрещено сводить канал к одному слову «бизнес» / «новости», если в постах видна конкретная роль.
                Пиши только то, что следует из фрагментов постов. Если мало данных — так и скажи.

                🧲 **Почему цепляет (или нет)** — на примерах топ-постов: какие психологические крючки сработали
                (любопытство, выгода, эмоция, соц. доказательство). На слабых (нерекламных) постах — чего не хватило.

                📉 **Где теряются просмотры** — конкретные причины просадок (первые строки, формат, время, длина).
                Если рекламы много — отметь это отдельной строкой.

                💡 **3 шага роста** — ровно 3 пункта, КАЖДЫЙ ОДНОЙ короткой строкой: действие + в скобках зачем.
                Без длинных объяснений (детальный план — в полной версии).
                """.formatted(
                channelTitle,
                metrics.postCount(),
                metrics.avgViews(),
                metrics.viewsDeltaPercent(),
                metrics.avgEngagementRate(),
                metrics.bestTimeSummary(),
                metrics.frequencyRecommendation(),
                blankIfEmpty(topPosts),
                blankIfEmpty(weakPosts),
                blankIfEmpty(samples),
                externalBlock,
                audienceBlock
        );
    }

    private static void appendSample(StringBuilder samples, Set<Long> usedIds, ChannelPostEntity post) {
        if (!usedIds.add(post.getId())) {
            return;
        }
        String text = post.getFullText() != null && !post.getFullText().isBlank()
                ? post.getFullText()
                : post.getTextPreview();
        if (text == null || text.isBlank()) {
            return;
        }
        String trimmed = text.length() > 400 ? text.substring(0, 397) + "…" : text;
        String adMark = post.isForwarded()
                ? "[РЕПОСТ ИЗ ДРУГОГО КАНАЛА] "
                : (looksLikeAd(text) ? "[ВОЗМОЖНО РЕКЛАМА] " : "");
        samples.append("---\n")
                .append(adMark)
                .append(trimmed.replace('\n', ' '))
                .append("\n(просм. ")
                .append(post.getViews() != null ? post.getViews() : 0)
                .append(")\n");
    }

    /**
     * Эвристика: похоже ли, что пост — рекламный/чужой, а не контент канала.
     * Сильнейший сигнал в РФ — токен маркировки "erid". Плюс типовые рекламные маркеры.
     */
    static boolean looksLikeAd(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        return t.contains("erid")
                || t.contains("на правах рекламы")
                || t.contains("реклама.")
                || t.contains("#реклама") || t.contains("#ad")
                || t.contains("рекламодател")
                || t.contains("по вопросам рекл")
                || t.contains("партнёрск") || t.contains("партнерск")
                || t.contains("промокод")
                || t.contains("utm_")
                || t.contains("реф. ссылк") || t.contains("реферальн");
    }

    private static String blankIfEmpty(StringBuilder sb) {
        return sb.isEmpty() ? "—" : sb.toString().trim();
    }

    private static String fallbackAnalysis(String channelTitle, AnalysisMetrics metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("📌 **Главное**\n");
        sb.append("За период ").append(metrics.postCount()).append(" постов, средние просмотры **")
                .append(metrics.avgViews()).append("** (").append(metrics.viewsDeltaPercent())
                .append("%), ER **").append(metrics.avgEngagementRate()).append("%**.\n\n");

        sb.append("💡 **3 шага роста**\n");
        sb.append("1. **Усильте первые 2 строки** — мозг решает читать/пролистать за секунду. ")
                .append("Начинайте с выгоды или вопроса, а не с приветствия.\n");
        sb.append("2. **Повторяйте форматы топ-постов** — публикуйте ")
                .append(metrics.frequencyRecommendation()).append(", лучшее время: ")
                .append(metrics.bestTimeSummary()).append(".\n");
        sb.append("3. **Добавьте вовлечение** — опросы и вопросы в конце поста повышают ER ")
                .append("за счёт эффекта незавершённости.\n");
        return sb.toString().trim();
    }
}
