package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.stats.AnalyticsService;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
import org.example.pulse_ai.text.TextHumanizer;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Перк «💰 Продающие посты»: готовый пост с фокусом на конверсию.
 * Честная persuasion: боль → выгода → доказательство → оффер → CTA.
 * Без фейкового дефицита, ложных обещаний и тёмных паттернов.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellingPostService {

    private static final String SYSTEM = """
            Ты — сильный копирайтер прямого отклика для Telegram. Пишешь продающие посты,
            которые конвертируют за счёт ЯСНОСТИ и доверия, а не обмана.

            Принципы (обязательно):
            - Честная persuasion: реальная боль аудитории → выгода → доказательство → оффер → один чёткий CTA.
            - НИКАКОГО фейкового дефицита, ложных «осталось 3 места», выдуманных цифр и гарантий.
            - Дефицит/сроки — только если пользователь сам указал их как реальные.
            - Пиши в стиле канала (смотри примеры топ-постов). Живой человек, не ИИ.
            - Telegram-формат: 700–1100 символов, абзацы, максимум 3 эмодзи, без markdown-заголовков.
            Ответ — только текст поста.""";

    private final LlmService llmService;
    private final AnalyticsService analyticsService;
    private final PulseAnalysisProperties analysisProperties;

    public String generate(ChannelEntity channel, String offerBrief) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(analysisProperties.getPeriodDays() - 1L);
        AnalysisMetrics metrics = analyticsService.analyze(channel.getId(), from, to);

        String prompt = """
                Канал: %s
                Что продаём (бриф от автора): %s

                Примеры удачных постов канала (держи их стиль):
                %s

                Напиши ОДИН продающий пост:
                • Заголовок-крючок в первой строке (боль или желание аудитории)
                • Развитие: почему это важно именно сейчас (без выдумок)
                • Выгода в результате, а не свойства
                • Доказательство (кейс/факт/логика) — если данных нет, аргументируй логикой, не ври
                • Оффер: что именно предлагаешь и что делать
                • Один понятный CTA в конце
                """.formatted(
                channel.getTitle(),
                offerBrief,
                formatSamples(metrics.topPosts())
        );

        try {
            String text = llmService.completeTextWithTimeout(
                    SYSTEM, prompt, analysisProperties.getLlmTimeoutSeconds(), 1800);
            if (text != null && !text.isBlank()) {
                return TextHumanizer.humanize(text.trim());
            }
        } catch (Exception ex) {
            log.warn("Selling post generation failed for channel {}: {}", channel.getId(), ex.getMessage());
        }
        return fallback(offerBrief);
    }

    private static String formatSamples(List<PostMetric> posts) {
        if (posts == null || posts.isEmpty()) {
            return "• мало данных — опирайся на тему канала";
        }
        StringBuilder sb = new StringBuilder();
        for (PostMetric p : posts.stream().limit(3).toList()) {
            sb.append("• «").append(p.title()).append("»\n");
        }
        return sb.toString().trim();
    }

    private static String fallback(String offerBrief) {
        return """
                %s

                Коротко: в чём ваша выгода и что делать дальше.
                Опишите результат клиента, добавьте один honest-аргумент (кейс или факт) и один призыв к действию.

                (Пост сгенерирован в упрощённом режиме — повторите запрос для полной версии.)"""
                .formatted(offerBrief).trim();
    }
}
