package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostAuditService {

    private static final String SYSTEM = """
            Ты — редактор Telegram-каналов и специалист по вниманию аудитории.
            Пишешь по-русски, коротко, без воды. Ключевые мысли — **жирным**.
            Объясняй через психологию: hook, обещание, незавершённость, соц. доказательство.
            Не выдумывай цифры. Если просмотров нет — оценивай только текст.""";

    private final LlmService llmService;
    private final PulseAnalysisProperties analysisProperties;

    public String audit(String channelTitle, String postText, Integer views, Integer channelAvgViews) {
        if (postText == null || postText.isBlank()) {
            return "❌ В посте нет текста для разбора.";
        }
        String metrics = views != null && views > 0
                ? "Просмотры поста: " + views
                + (channelAvgViews != null && channelAvgViews > 0
                ? " (среднее по каналу ~" + channelAvgViews + ")" : "")
                : "Просмотры неизвестны — оценивай только текст.";

        String prompt = """
                Разбери пост из канала «%s».

                %s

                Текст поста:
                ---
                %s
                ---

                Структура ответа (эмодзи + **жирные** акценты, коротко):

                🎯 **Вердикт** — зашёл / средне / слабо (1 строка почему)

                🪝 **Первая строка** — цепляет или нет, что переписать

                📉 **Где теряется внимание** — 2–3 конкретных места

                ✨ **3 правки** — ровно 3 пункта, каждый одной строкой
                """.formatted(
                channelTitle != null ? channelTitle : "канал",
                metrics,
                postText.length() > 3500 ? postText.substring(0, 3497) + "…" : postText
        ).trim();

        try {
            return llmService.completeTextWithTimeout(
                    SYSTEM, prompt, analysisProperties.getLlmTimeoutSeconds(), 2000);
        } catch (Exception ex) {
            log.warn("Post audit LLM failed: {}", ex.getMessage());
            return fallback(postText);
        }
    }

    private static String fallback(String text) {
        String hook = text.lines().findFirst().orElse("").trim();
        boolean weakHook = hook.length() < 15 || hook.toLowerCase().startsWith("привет");
        return """
                🎯 **Вердикт**
                """ + (weakHook ? "Слабый старт — первая строка не обещает выгоду." : "Есть зацепка, но можно усилить конкретикой.") + """

                🪝 **Первая строка**
                Начните с боли или цифры — не с приветствия.

                📉 **Где теряется внимание**
                Длинные вступления и общие фразы без примера.

                ✨ **3 правки**
                1. **Перепишите первые 2 строки** под выгоду читателя
                2. **Один конкретный пример** вместо абстракции
                3. **Вопрос или CTA** в конце — поднимет реакции""";
    }
}
