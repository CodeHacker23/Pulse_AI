package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
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
            - Последняя строка — призыв к действию или вопрос аудитории.

            РАЗМЕТКА (Telegram): только **жирный** и _курсив_. Не используй # и HTML-теги.

            ДЛИНА — варьируй, не делай все посты длинными:
            - Иногда короткий пост (350–550 символов): заголовок + 2–3 коротких абзаца.
            - Иногда средний (600–900 символов).
            - НИКОГДА не длиннее 1000 символов и не сплошным полотном — клиповое мышление, длинное не дочитывают.

            ФОРМАТ:
            - Реальные переносы строк (\\n), НЕ пиши всё одним абзацем.
            - 2–5 эмодзи на весь пост, к месту.
            - Без «в этом посте», без «подписывайтесь».
            - Без кавычек-ёлочек и длинных тире. Обычная пунктуация.
            Ответ — только текст поста, без пояснений.""";

    private final LlmService llmService;

    public String generateDraft(String channelTitle, ContentIdeaEntity idea, AnalysisMetrics metrics, int timeoutSeconds) {
        try {
            String userPrompt = buildPrompt(channelTitle, idea, metrics);
            String text = llmService.completeTextWithTimeout(SYSTEM, userPrompt, timeoutSeconds, 1800);
            return text != null ? TextHumanizer.humanize(text.trim()) : fallbackDraft(idea);
        } catch (Exception ex) {
            log.warn("Draft generation failed: {}", ex.getMessage());
            return fallbackDraft(idea);
        }
    }

    private static String buildPrompt(String channelTitle, ContentIdeaEntity idea, AnalysisMetrics metrics) {
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
        return """
                Канал: %s
                Идея поста: %s
                Почему зайдёт: %s
                Формат: %s
                Лучшее время: %s

                Примеры удачных постов канала:
                %s

                Напиши один готовый пост по этой идее. Зацепи с первой строки.
                Заголовок — отдельной строкой, потом пустая строка, потом короткие абзацы через пустую строку.
                """.formatted(
                channelTitle,
                idea.getTitle(),
                idea.getReason() != null ? idea.getReason() : "—",
                idea.getFormat() != null ? idea.getFormat() : "текст",
                idea.getSuggestedDay() != null ? idea.getSuggestedDay() : "будни вечером",
                samples
        ).trim();
    }

    private static String fallbackDraft(ContentIdeaEntity idea) {
        return idea.getTitle() + "\n\n"
                + (idea.getReason() != null ? idea.getReason() : "Разверните мысль в 3–4 абзаца.")
                + "\n\nЧерновик сгенерирован в упрощённом режиме — полная версия в платном запросе.";
    }
}
