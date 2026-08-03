package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
import org.example.pulse_ai.telegram.TelegramLimits;
import org.example.pulse_ai.text.TextHumanizer;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostDraftService {

    private static final String SYSTEM = """
            Ты — копирайтер Telegram-каналов. Пишешь посты, которые хочется дочитать.
            Стиль: как у автора канала (смотри примеры топ-постов), без коуч-штампов и «5 советов».

            СТРУКТУРА (обязательно, это Telegram — читают с телефона):
            - Первая строка — цепляющий заголовок, обёрнутый в **звёздочки** (будет жирным), с 1 эмодзи. Отдельной строкой.
            - Пустая строка после заголовка.
            - Тело: короткие абзацы по 1–3 предложения, между абзацами ПУСТАЯ СТРОКА.
            - 1–2 ключевые мысли выдели _курсивом_ (обрамляй нижними подчёркиваниями), чтобы разбить полотно текста.
            - Где уместно — список пунктами (каждый с новой строки, можно с эмодзи-маркером).
            - Последняя строка — призыв к действию или вопрос аудитории (используй CTA из идеи, если дан).

            РАЗМЕТКА (Telegram): только **жирный** и _курсив_. Не используй # и HTML-теги.

            ДЛИНА — варьируй, не делай все посты длинными:
            - Иногда короткий пост (350–550 символов): заголовок + 2–3 коротких абзаца.
            - Иногда средний (600–900 символов).
            - НИКОГДА не длиннее 1000 символов и не сплошным полотном.
            - Формат «короткий» или пост под фото — цель ≤ 920 символов
              (лимит подписи Telegram к фото — 1024 HTML; длиннее = только текст без картинки).

            ФОРМАТ:
            - Реальные переносы строк (\\n), НЕ пиши всё одним абзацем.
            - 2–5 эмодзи на весь пост, к месту.
            - Без «в этом посте», без «подписывайтесь».
            - Без кавычек-ёлочек и длинных тире. Обычная пунктуация.
            Ответ — только текст поста, без пояснений.""";

    private final LlmService llmService;

    public String generateDraft(String channelTitle, ContentIdeaEntity idea, AnalysisMetrics metrics, int timeoutSeconds) {
        return generateDraft(channelTitle, idea, metrics, timeoutSeconds, null, null);
    }

    public String generateDraft(
            String channelTitle,
            ContentIdeaEntity idea,
            AnalysisMetrics metrics,
            int timeoutSeconds,
            String stylePrompt
    ) {
        return generateDraft(channelTitle, idea, metrics, timeoutSeconds, stylePrompt, null);
    }

    public String generateDraft(
            String channelTitle,
            ContentIdeaEntity idea,
            AnalysisMetrics metrics,
            int timeoutSeconds,
            String stylePrompt,
            String analysisBrief
    ) {
        try {
            String userPrompt = buildPrompt(channelTitle, idea, metrics, stylePrompt, analysisBrief);
            String text = llmService.completeTextWithTimeout(SYSTEM, userPrompt, timeoutSeconds, 1800);
            return text != null ? TextHumanizer.humanize(text.trim()) : fallbackDraft(idea);
        } catch (Exception ex) {
            log.warn("Draft generation failed: {}", ex.getMessage());
            return fallbackDraft(idea);
        }
    }

    /**
     * Сжимает пост под лимит caption к фото (Telegram ≤ 1024 HTML).
     * Сохраняет смысл, заголовок и CTA.
     */
    public String shortenForPhotoCaption(String text, int timeoutSeconds) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (TelegramLimits.fitsPhotoCaption(text)) {
            return text;
        }
        try {
            String prompt = """
                    Сожми пост для публикации С ФОТО в Telegram.
                    Лимит подписи: не больше %d символов markdown (цель), итог после HTML ≤ 1024.

                    Правила:
                    - сохрани заголовок (первая строка) и главный смысл;
                    - сохрани финальный CTA/вопрос, если есть;
                    - убери воду и повторы, короткие абзацы через пустую строку;
                    - разметка только **жирный** и _курсив_;
                    - ответ — только текст поста, без пояснений.

                    Исходный пост:
                    %s
                    """.formatted(TelegramLimits.PHOTO_SAFE_MARKDOWN, text);
            String out = llmService.completeTextWithTimeout(
                    "Ты редактор Telegram-постов. Сжимаешь текст без потери смысла.",
                    prompt,
                    timeoutSeconds,
                    1400
            );
            if (out == null || out.isBlank()) {
                return hardTrimForCaption(text);
            }
            String human = TextHumanizer.humanize(out.trim());
            return TelegramLimits.fitsPhotoCaption(human) ? human : hardTrimForCaption(human);
        } catch (Exception ex) {
            log.warn("Shorten for caption failed: {}", ex.getMessage());
            return hardTrimForCaption(text);
        }
    }

    private static String hardTrimForCaption(String text) {
        String t = text.trim();
        while (t.length() > 200 && !TelegramLimits.fitsPhotoCaption(t)) {
            int cut = Math.max(200, (int) (t.length() * 0.85));
            int breakAt = t.lastIndexOf('\n', cut);
            if (breakAt < 150) {
                breakAt = cut;
            }
            t = t.substring(0, breakAt).trim() + "…";
        }
        return t;
    }

    private static String buildPrompt(
            String channelTitle,
            ContentIdeaEntity idea,
            AnalysisMetrics metrics,
            String stylePrompt,
            String analysisBrief
    ) {
        StringBuilder samples = new StringBuilder();
        int n = 0;
        for (PostMetric post : metrics.topPosts()) {
            if (n++ >= 3) {
                break;
            }
            samples.append("• «").append(post.title()).append("»\n");
        }
        if (samples.isEmpty()) {
            samples.append("• мало данных — опирайся на название канала\n");
        }
        String cta = idea.getCta() != null && !idea.getCta().isBlank()
                ? idea.getCta()
                : "— сформулируй сам короткий вопрос/призыв";
        String gap = idea.getClosesGap() != null && !idea.getClosesGap().isBlank()
                ? idea.getClosesGap()
                : "н/д";
        boolean preferShort = idea.getFormat() != null
                && (idea.getFormat().contains("коротк") || "опрос".equals(idea.getFormat()));
        return """
                Канал: %s
                %s%sИдея поста: %s
                Почему зайдёт: %s
                Закрывает просадку: %s
                Формат: %s
                CTA в финале: %s
                Лучшее время: %s
                %s
                Примеры удачных постов канала (вторичный ориентир, если не противоречат стилю выше):
                %s

                Напиши один готовый пост по этой идее. Зацепи с первой строки — учти бриф разбора, если он есть.
                Заголовок — отдельной строкой, потом пустая строка, потом короткие абзацы через пустую строку.
                """.formatted(
                channelTitle,
                StylePromptBlock.format(stylePrompt),
                AnalysisBriefForContent.promptBlock(analysisBrief),
                idea.getTitle(),
                idea.getReason() != null ? idea.getReason() : "—",
                gap,
                idea.getFormat() != null ? idea.getFormat() : "текст",
                cta,
                idea.getSuggestedDay() != null ? idea.getSuggestedDay() : "будни вечером",
                preferShort
                        ? "Длина: короткий пост, цель ≤ " + TelegramLimits.PHOTO_SAFE_MARKDOWN + " символов (под фото).\n"
                        : "",
                samples
        ).trim();
    }

    private static String fallbackDraft(ContentIdeaEntity idea) {
        String cta = idea.getCta() != null && !idea.getCta().isBlank()
                ? "\n\n" + idea.getCta()
                : "";
        return idea.getTitle() + "\n\n"
                + (idea.getReason() != null ? idea.getReason() : "Разверните мысль в 3–4 абзаца.")
                + cta
                + "\n\nЧерновик сгенерирован в упрощённом режиме — полная версия в платном запросе.";
    }
}
