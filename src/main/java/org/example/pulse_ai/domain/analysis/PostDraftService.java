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

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostDraftService {

    /** Примерно каждый второй пост — с вопросом к аудитории, остальные без. */
    static final int AUDIENCE_QUESTION_CHANCE_PERCENT = 45;

    /**
     * Общие правила голоса: стиль автора, интерес без «ИИ-тона».
     * Тип финала (с вопросом / без) задаётся отдельно на каждый пост — не угадывай сам.
     */
    static final String VOICE_AND_ENGAGE = """
            ГОЛОС:
            - Пиши так, будто это сам автор канала после удачных постов — не «помощник», не коуч, не маркетолог.
            - Копируй ритм и лексику примеров/стиля автора. Если стиль дан явно — он важнее всего.
            - Без канцелярита и ИИ-штампов: «в современном мире», «важно отметить», «давайте разберёмся»,
              «не секрет», «в заключение», «стоит отметить», «каждый из нас».
            - Не выдумывай факты, цифры, цитаты, кейсы. Нет данных — пиши общо или опусти деталь.
            - Опирайся только на идею, бриф и примеры. Не добавляй «из головы» то, чего нет во входе.

            ВОВЛЕЧЕНИЕ (под капотом, не объясняй читателю):
            - Первая строка — крючок: конкретная сцена, цифра из контекста, антитеза или узнаваемый сбой.
            - Дальше — напряжение или узнавание, потом ясный вывод / punchline.
            - Конкретика сильнее абстракций. Короткие фразы. Живой разговорный ритм канала.

            ФИНАЛ:
            - Тип финала указан в задании этого поста — выполни его, не смешивай.
            - Если нужен вопрос: один, живой, по теме, без штампов «а вы как?», «согласны?», «пишите в комменты».
            - Если вопрос не нужен: утверждение / punchline, без риторического вопроса в конце.""";

    private static final String SYSTEM = """
            Ты пишешь посты для Telegram-канала от лица автора. Заменяешь рутину написания, не выглядишь как ИИ.
            Стиль: как у автора (смотри промпт стиля и примеры топ-постов), без коуч-штампов и «5 советов».

            """ + VOICE_AND_ENGAGE + """

            СТРУКТУРА (Telegram — читают с телефона):
            - Первая строка — цепляющий заголовок в **звёздочках** (жирный), с 1 эмодзи. Отдельной строкой.
            - Пустая строка после заголовка.
            - Тело: короткие абзацы по 1–3 предложения, между абзацами ПУСТАЯ СТРОКА.
            - 1–2 ключевые мысли выдели _курсивом_ (нижние подчёркивания).
            - Где уместно — список пунктами (каждый с новой строки, можно с эмодзи-маркером).
            - Последняя строка — по заданию этого поста: либо punchline, либо один живой вопрос.

            РАЗМЕТКА (Telegram): только **жирный** и _курсив_. Не используй # и HTML-теги.

            ДЛИНА — варьируй, не делай все посты длинными:
            - Иногда короткий пост (350–550 символов): заголовок + 2–3 коротких абзаца.
            - Иногда средний (600–900 символов).
            - НИКОГДА не длиннее 1000 символов и не сплошным полотном.
            - Формат «короткий» или пост под фото — цель ≤ 920 символов
              (лимит подписи Telegram к фото — 1024 HTML; длиннее = только текст без картинки).

            ФОРМАТ:
            - Реальные переносы строк (\\n), НЕ пиши всё одним абзацем.
            - 2–5 эмодзи на весь пост, к месту (если автор редко использует эмодзи — меньше).
            - Без «в этом посте», без «подписывайтесь».
            - Без кавычек-ёлочек и длинных тире. Обычная пунктуация.
            Ответ — только текст поста, без пояснений.""";

    private static final String TIGHTEN_SYSTEM = """
            Ты редактор Telegram-постов. Сжимаешь текст, сохраняя голос автора и смысл.
            """ + VOICE_AND_ENGAGE + """
            Не добавляй новых фактов. Убери воду и повторы. Ответ — только текст поста.""";

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
            String userPrompt = buildPrompt(
                    channelTitle, idea, metrics, stylePrompt, analysisBrief, rollAudienceQuestion());
            String text = llmService.completeTextWithTimeout(SYSTEM, userPrompt, timeoutSeconds, 1800);
            return text != null ? TextHumanizer.humanize(text.trim()) : fallbackDraft(idea);
        } catch (Exception ex) {
            log.warn("Draft generation failed: {}", ex.getMessage());
            return fallbackDraft(idea);
        }
    }

    static boolean rollAudienceQuestion() {
        return ThreadLocalRandom.current().nextInt(100) < AUDIENCE_QUESTION_CHANCE_PERCENT;
    }

    static String audienceFinaleRule(boolean withQuestion) {
        if (withQuestion) {
            return """
                    ФИНАЛ ЭТОГО ПОСТА: с вопросом к аудитории.
                    Последняя строка — один живой вопрос по теме (не «а вы?», не «согласны?», не «пишите в комменты»).""";
        }
        return """
                ФИНАЛ ЭТОГО ПОСТА: без вопроса.
                Последняя строка — утверждение или punchline. Риторический вопрос не ставь.""";
    }

    /**
     * Укорачивает пост по смыслу (редактирование): стиль автора, интерес.
     * Тип финала исходника сохраняем (вопрос остаётся вопросом).
     */
    public String tighten(String text, String stylePrompt, int timeoutSeconds) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            String prompt = """
                    Сделай пост короче и плотнее — как будто автор сам подчистил черновик.

                    %sЦель: примерно 55–70%% длины исходника (если исходник уже короткий — чуть ужми воду).
                    Сохрани заголовок (первая строка) и главный смысл. Не выдумывай новое.
                    Если в конце был вопрос к аудитории — оставь один, живой. Если не было — не добавляй.
                    Короткие абзацы через пустую строку. Разметка только **жирный** и _курсив_.
                    Ответ — только текст поста.

                    Исходный пост:
                    %s
                    """.formatted(StylePromptBlock.format(stylePrompt), text);
            String out = llmService.completeTextWithTimeout(
                    TIGHTEN_SYSTEM, prompt, timeoutSeconds, 1400);
            if (out == null || out.isBlank()) {
                return text;
            }
            return TextHumanizer.humanize(out.trim());
        } catch (Exception ex) {
            log.warn("Tighten post failed: {}", ex.getMessage());
            return text;
        }
    }

    /**
     * Сжимает пост под лимит caption к фото (Telegram ≤ 1024 HTML).
     * Сохраняет смысл, заголовок и тип финала (вопрос не выкидывать, новый не придумывать).
     */
    public String shortenForPhotoCaption(String text, int timeoutSeconds) {
        return shortenForPhotoCaption(text, null, timeoutSeconds);
    }

    public String shortenForPhotoCaption(String text, String stylePrompt, int timeoutSeconds) {
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

                    %sПравила:
                    - сохрани заголовок (первая строка) и главный смысл;
                    - если в конце был вопрос — оставь один короткий; если не было — не добавляй;
                    - убери воду и повторы, короткие абзацы через пустую строку;
                    - не выдумывай фактов;
                    - разметка только **жирный** и _курсив_;
                    - ответ — только текст поста, без пояснений.

                    Исходный пост:
                    %s
                    """.formatted(TelegramLimits.PHOTO_SAFE_MARKDOWN, StylePromptBlock.format(stylePrompt), text);
            String out = llmService.completeTextWithTimeout(
                    TIGHTEN_SYSTEM,
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
            String analysisBrief,
            boolean withAudienceQuestion
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
        String ctaHint = idea.getCta() != null && !idea.getCta().isBlank()
                ? idea.getCta()
                : "—";
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
                Ориентир CTA из идеи: %s
                %s
                Лучшее время: %s
                %s
                Примеры удачных постов канала (вторичный ориентир, если не противоречат стилю выше):
                %s

                Напиши один готовый пост по этой идее. Зацепи с первой строки — учти бриф разбора, если он есть.
                Заголовок — отдельной строкой, потом пустая строка, потом короткие абзацы через пустую строку.
                Не выходи из роли автора. Не добавляй фактов вне идеи и брифа.
                """.formatted(
                channelTitle,
                StylePromptBlock.format(stylePrompt),
                AnalysisBriefForContent.promptBlock(analysisBrief),
                idea.getTitle(),
                idea.getReason() != null ? idea.getReason() : "—",
                gap,
                idea.getFormat() != null ? idea.getFormat() : "текст",
                ctaHint,
                audienceFinaleRule(withAudienceQuestion),
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
