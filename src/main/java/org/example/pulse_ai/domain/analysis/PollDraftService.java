package org.example.pulse_ai.domain.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Собирает нативный опрос Telegram из идеи формата «опрос»: вопрос + 2–6 вариантов.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PollDraftService {

    private static final int TIMEOUT_SECONDS = 35;

    private static final String SYSTEM = """
            Ты готовишь опрос для Telegram-канала. Нужен короткий цепляющий вопрос и 3–5 вариантов ответа.
            Вопрос — как первая строка в ленте: конкретика, без воды, до 120 символов.
            Варианты — короткие (до 80 символов), взаимоисключающие, на языке ЦА канала.
            Не делай вариант «другое», если не просили. Без эмодзи-спама.
            Ответ строго JSON: {"question":"...","options":["...","..."]}""";

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public record PollDraft(String question, List<String> options) {
    }

    public static boolean isPollFormat(String format) {
        if (format == null) {
            return false;
        }
        String f = format.toLowerCase(Locale.ROOT).trim();
        return f.contains("опрос") || f.equals("poll") || f.contains("quiz");
    }

    public PollDraft generate(String channelTitle, ContentIdeaEntity idea) {
        try {
            String prompt = """
                    Канал: «%s»
                    Идея: «%s»
                    Почему зайдёт: %s

                    Сделай опрос по этой идее.""".formatted(
                    channelTitle != null ? channelTitle : "",
                    idea.getTitle() != null ? idea.getTitle() : "",
                    idea.getReason() != null ? idea.getReason() : ""
            );
            String json = llmService.completeJsonWithTimeout(SYSTEM, prompt, TIMEOUT_SECONDS);
            return parse(json, idea);
        } catch (Exception ex) {
            log.warn("Poll draft LLM failed: {}", ex.getMessage());
            return fallback(idea);
        }
    }

    private PollDraft parse(String json, ContentIdeaEntity idea) throws Exception {
        JsonNode root = objectMapper.readTree(clean(json));
        String question = root.path("question").asText("").trim();
        if (question.isBlank()) {
            question = idea.getTitle() != null ? idea.getTitle() : "Ваш голос?";
        }
        if (question.length() > 255) {
            question = question.substring(0, 252) + "…";
        }
        List<String> options = new ArrayList<>();
        JsonNode arr = root.path("options");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String opt = n.asText("").trim();
                if (!opt.isBlank()) {
                    options.add(opt.length() > 100 ? opt.substring(0, 97) + "…" : opt);
                }
                if (options.size() >= 8) {
                    break;
                }
            }
        }
        if (options.size() < 2) {
            return fallback(idea);
        }
        return new PollDraft(question, options);
    }

    public static PollDraft fallback(ContentIdeaEntity idea) {
        String q = idea.getTitle() != null && !idea.getTitle().isBlank()
                ? idea.getTitle()
                : "Как вам такой формат?";
        if (q.length() > 255) {
            q = q.substring(0, 252) + "…";
        }
        return new PollDraft(q, List.of(
                "Готов платить",
                "Только бесплатно",
                "Зависит от качества",
                "Пока не определился"
        ));
    }

    public static List<String> parseOptionsLines(String text) {
        List<String> options = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return options;
        }
        for (String line : text.split("\\R")) {
            String opt = line.trim()
                    .replaceFirst("^[\\d]+[.)\\-]\\s*", "")
                    .replaceFirst("^[-•*]\\s*", "")
                    .trim();
            if (!opt.isBlank()) {
                options.add(opt.length() > 100 ? opt.substring(0, 97) + "…" : opt);
            }
            if (options.size() >= 10) {
                break;
            }
        }
        return options;
    }

    private static String clean(String json) {
        if (json == null) {
            return "{}";
        }
        String s = json.trim();
        if (s.startsWith("```")) {
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a >= 0 && b > a) {
                return s.substring(a, b + 1);
            }
        }
        return s;
    }
}
