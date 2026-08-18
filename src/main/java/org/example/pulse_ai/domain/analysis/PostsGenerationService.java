package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.persistence.entity.GeneratedPostEntity;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
import org.example.pulse_ai.text.TextHumanizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostsGenerationService {

    private static final String SYSTEM = """
            Ты пишешь посты для Telegram-канала от лица автора. Заменяешь рутину написания, не выглядишь как ИИ.
            Без коуч-штампов, без «5 советов», без длинных тире и кавычек-ёлочек.

            """ + PostDraftService.VOICE_AND_ENGAGE + """

            СТРУКТУРА каждого поста (Telegram, читают с телефона):
            - Первая строка — цепляющий заголовок в **звёздочках** (жирный) с 1 эмодзи, отдельной строкой.
            - Дальше короткие абзацы 1–3 предложения, между абзацами ПУСТАЯ СТРОКА.
            - 1–2 ключевые мысли выдели _курсивом_, чтобы разбить полотно текста.
            - Где уместно — список пунктами с новой строки.
            - Финал — по заданию конкретной идеи: punchline или один живой вопрос.

            РАЗМЕТКА: только **жирный** и _курсив_. Без # и HTML.
            ДЛИНА — варьируй между постами: часть коротких (350–550), часть средних (600–900).
            Никогда не длиннее 1000 символов и не сплошным полотном.
            Короткие посты и посты под фото — цель ≤ 920 (лимит подписи Telegram к фото — 1024 HTML).
            Используй реальные переносы строк (в JSON это \\n). НЕ пиши пост одним абзацем.
            2–5 эмодзи к месту (если автор скуп на эмодзи — меньше).
            Не выдумывай факты вне идей и брифа.
            Ответ — только JSON.""";

    private final LlmService llmService;
    private final GeneratedPostService generatedPostService;

    public List<GeneratedPostEntity> generatePosts(
            long requestId,
            String channelTitle,
            List<ContentIdeaEntity> ideas,
            AnalysisMetrics metrics,
            int postCount,
            int timeoutSeconds
    ) {
        return generatePosts(requestId, channelTitle, ideas, metrics, postCount, timeoutSeconds, null, null);
    }

    public List<GeneratedPostEntity> generatePosts(
            long requestId,
            String channelTitle,
            List<ContentIdeaEntity> ideas,
            AnalysisMetrics metrics,
            int postCount,
            int timeoutSeconds,
            String stylePrompt
    ) {
        return generatePosts(requestId, channelTitle, ideas, metrics, postCount, timeoutSeconds, stylePrompt, null);
    }

    public List<GeneratedPostEntity> generatePosts(
            long requestId,
            String channelTitle,
            List<ContentIdeaEntity> ideas,
            AnalysisMetrics metrics,
            int postCount,
            int timeoutSeconds,
            String stylePrompt,
            String analysisBrief
    ) {
        List<ContentIdeaEntity> source = ideas.stream().limit(postCount).toList();
        if (source.isEmpty()) {
            return List.of();
        }
        List<GeneratedPostEntity> existing = new ArrayList<>();
        for (ContentIdeaEntity idea : source) {
            generatedPostService.findByRequestAndIdea(requestId, idea.getId()).ifPresent(existing::add);
        }
        if (existing.size() >= postCount) {
            return existing.stream().limit(postCount).toList();
        }

        try {
            String prompt = buildBatchPrompt(channelTitle, source, metrics, stylePrompt, analysisBrief);
            String json = llmService.completeJsonWithTimeout(SYSTEM, prompt, timeoutSeconds);
            return parseAndSave(requestId, source, json);
        } catch (Exception ex) {
            log.warn("Batch posts generation failed: {}", ex.getMessage());
            return generateFallbackPosts(requestId, source);
        }
    }

    private List<GeneratedPostEntity> parseAndSave(long requestId, List<ContentIdeaEntity> ideas, String json) {
        List<GeneratedPostEntity> result = new ArrayList<>();
        String cleaned = json.trim();
        if (cleaned.startsWith("```")) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(cleaned);
            var posts = root.path("posts");
            for (int i = 0; i < ideas.size() && i < posts.size(); i++) {
                String text = TextHumanizer.humanize(posts.get(i).path("text").asText("").trim());
                if (text.isBlank()) {
                    continue;
                }
                result.add(generatedPostService.saveRegeneratedDraft(requestId, ideas.get(i), text));
            }
        } catch (Exception ex) {
            log.warn("Parse batch posts failed: {}", ex.getMessage());
        }
        if (result.size() < ideas.size()) {
            result.addAll(generateFallbackPosts(requestId, ideas.subList(result.size(), ideas.size())));
        }
        return result;
    }

    private List<GeneratedPostEntity> generateFallbackPosts(long requestId, List<ContentIdeaEntity> ideas) {
        List<GeneratedPostEntity> result = new ArrayList<>();
        for (ContentIdeaEntity idea : ideas) {
            String text = idea.getTitle() + "\n\n"
                    + (idea.getReason() != null ? idea.getReason() : "Разверните мысль в 3–4 абзаца.");
            result.add(generatedPostService.saveRegeneratedDraft(requestId, idea, text));
        }
        return result;
    }

    private static String buildBatchPrompt(
            String channelTitle,
            List<ContentIdeaEntity> ideas,
            AnalysisMetrics metrics,
            String stylePrompt,
            String analysisBrief
    ) {
        StringBuilder ideaBlock = new StringBuilder();
        int n = 1;
        for (ContentIdeaEntity idea : ideas) {
            String finale = PostDraftService.rollAudienceQuestion()
                    ? "финал: вопрос к аудитории"
                    : "финал: без вопроса, punchline";
            ideaBlock.append(n++).append(". ").append(idea.getTitle())
                    .append(" — ").append(finale).append('\n');
        }
        StringBuilder samples = new StringBuilder();
        int s = 0;
        for (PostMetric post : metrics.topPosts()) {
            if (s++ >= 2) {
                break;
            }
            samples.append("• «").append(post.title()).append("»\n");
        }
        return """
                Канал: %s
                %s%sНапиши %d готовых поста для Telegram по идеям ниже (по одному посту на идею).
                Если есть бриф разбора — закрой его просадки: крючок в первой строке, разнообразие подачи.
                У каждой идеи свой тип финала — соблюдай его. Не делай все посты с вопросом и не делай все без.

                Идеи:
                %s

                Примеры удачных постов канала (вторичный ориентир, если не противоречат стилю выше):
                %s

                JSON:
                {"posts":[{"text":"..."}]}
                """.formatted(
                channelTitle,
                StylePromptBlock.format(stylePrompt),
                AnalysisBriefForContent.promptBlock(analysisBrief),
                ideas.size(),
                ideaBlock,
                samples
        ).trim();
    }
}
