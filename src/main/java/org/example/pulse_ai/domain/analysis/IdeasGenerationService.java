package org.example.pulse_ai.domain.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
import org.example.pulse_ai.stats.model.TopicMetric;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdeasGenerationService {

  private static final String SYSTEM_PROMPT = """
      Ты — копирайтер Telegram-каналов. Пишешь заголовки, по которым ХОЧЕТСЯ тапнуть.
      НЕ комик. НЕ коуч. НЕ «эксперт с 10 советами». Никакого стендапа, чёрного юмора и кринжа.

      Формула сильного заголовка:
      1) конкретика (цифра, ситуация, деталь) в первых 5–7 словах;
      2) обещание: что узнаю / что получу / что меня коснётся;
      3) тон канала — как в его топ-постах, только острее.

      Запрещено в title: «в этом посте», «разберём», «полезные советы», «секреты успеха»,
      «как прокачать», «5 способов», «вы узнаете», шутки ради шутки, пустые вопросы без боли.

      reason — одно короткое предложение: какая боль/любопытство заставит дочитать.
      Ответ — ТОЛЬКО валидный JSON, без markdown.""";

  private final LlmService llmService;
  private final ObjectMapper objectMapper;

  public List<ContentIdeaEntity> generateIdeas(
      Long requestId,
      String channelTitle,
      AnalysisMetrics metrics,
      int ideaCount
  ) {
    return generateIdeas(requestId, channelTitle, metrics, ideaCount, 60);
  }

  public List<ContentIdeaEntity> generateIdeas(
      Long requestId,
      String channelTitle,
      AnalysisMetrics metrics,
      int ideaCount,
      int timeoutSeconds
  ) {
    try {
      String userPrompt = buildPrompt(channelTitle, metrics, ideaCount);
      String json = llmService.completeJsonWithTimeout(
          SYSTEM_PROMPT, userPrompt, timeoutSeconds);
      return parseIdeas(requestId, json, ideaCount, metrics);
    } catch (Exception ex) {
      log.warn("LLM ideas failed ({}s), using fallback: {}", timeoutSeconds, ex.getMessage());
      return fallbackIdeas(requestId, metrics, ideaCount);
    }
  }

  private String buildPrompt(String channelTitle, AnalysisMetrics metrics, int ideaCount) {
    StringBuilder topPosts = new StringBuilder();
    for (PostMetric post : metrics.topPosts()) {
      topPosts.append("• «").append(post.title()).append("» — ")
          .append(post.views()).append(" просм.\n");
    }
    if (topPosts.isEmpty()) {
      topPosts.append("— мало данных, опирайся на тематику названия канала\n");
    }

    StringBuilder weakPosts = new StringBuilder();
    for (PostMetric post : metrics.worstPosts()) {
      weakPosts.append("• «").append(post.title()).append("» — ")
          .append(post.views()).append(" просм.\n");
    }
    if (weakPosts.isEmpty()) {
      weakPosts.append("—\n");
    }

    StringBuilder topics = new StringBuilder();
    for (TopicMetric topic : metrics.workingTopics()) {
      topics.append("• ").append(topic.topic())
          .append(" (~").append(topic.avgViews()).append(" просм.)\n");
    }
    if (topics.isEmpty()) {
      topics.append("—\n");
    }

    return """
        Канал: «%s»
        Придумай ровно %d САМЫХ СИЛЬНЫХ идеи постов на ближайшие 7–14 дней (только лучшие, без воды).

        Что уже ЗАХОДИТ (копируй структуру и тон, не тему дословно):
        %s

        Что НЕ зашло (не повторяй такие заголовки):
        %s

        Работающие темы:
        %s

        Метрики: %d постов, ~%d просм. в среднем, лучшее время: %s

        Примеры ПЛОХИХ title (так НЕ пиши):
        ✗ «Смешная история про вашу нишу»
        ✗ «5 советов как улучшить контент»
        ✗ «Почему важно развивать канал в 2026»
        ✗ «Разберём главные тренды отрасли»

        Примеры СИЛЬНЫХ title (адаптируй под нишу канала):
        ✓ «Потратил 40 часов на X — ошибка оказалась в одной строчке»
        ✓ «Опрос: сколько вы реально тратите на Y? (честно)»
        ✓ «Клиент попросил Z. Показываю, что вышло — без прикрас»
        ✓ «Мне 3 года говорили «делай так». Перестал — просмотры выросли»

        Требования:
        - title: готовая первая строка поста, до 12 слов, с крючком в начале;
        - reason: 1 предложение — что зацепит и что человек унесёт;
        - format: longread | короткий | опрос | кейс;
        - suggested_day: день недели на русском.

        Верни JSON:
        {
          "ideas": [
            {
              "title": "string",
              "reason": "string",
              "format": "string",
              "suggested_day": "string"
            }
          ]
        }
        """.formatted(
        channelTitle,
        ideaCount,
        topPosts.toString().trim(),
        weakPosts.toString().trim(),
        topics.toString().trim(),
        metrics.postCount(),
        metrics.avgViews(),
        metrics.bestTimeSummary()
    );
  }

  private List<ContentIdeaEntity> parseIdeas(
      Long requestId, String json, int ideaCount, AnalysisMetrics metrics
  ) throws Exception {
    JsonNode root = objectMapper.readTree(cleanJson(json));
    JsonNode ideasNode = root.path("ideas");
    List<ContentIdeaEntity> ideas = new ArrayList<>();
    for (int i = 0; i < ideasNode.size() && i < ideaCount; i++) {
      JsonNode node = ideasNode.get(i);
      String title = node.path("title").asText("").trim();
      if (title.isBlank() || looksGeneric(title)) {
        continue;
      }
      ContentIdeaEntity idea = new ContentIdeaEntity();
      idea.setRequestId(requestId);
      idea.setSortOrder((short) (ideas.size() + 1));
      idea.setTitle(title);
      idea.setReason(node.path("reason").asText("Цепляет на боли аудитории канала."));
      idea.setFormat(node.path("format").asText("короткий"));
      idea.setSuggestedDay(node.path("suggested_day").asText(defaultDay(metrics)));
      ideas.add(idea);
    }
    if (ideas.isEmpty()) {
      throw new IllegalStateException("Empty or generic ideas from LLM");
    }
    while (ideas.size() < ideaCount) {
      ideas.addAll(fallbackIdeas(requestId, metrics, ideaCount - ideas.size()));
      break;
    }
    return ideas.stream().limit(ideaCount).toList();
  }

  /** Отсекает шаблонные заголовки, которые LLM любит выдавать под видом «крючка». */
  private static boolean looksGeneric(String title) {
    String t = title.toLowerCase(Locale.ROOT);
    return t.contains("стендап")
        || t.contains("с юмором")
        || t.contains("с перчиком")
        || t.contains("5 совет")
        || t.contains("10 совет")
        || t.contains("полезн")
        || t.contains("секрет")
        || t.contains("прокач")
        || t.contains("разбер")
        || t.contains("в этом посте")
        || t.startsWith("как улучш")
        || t.startsWith("почему важно");
  }

  private List<ContentIdeaEntity> fallbackIdeas(Long requestId, AnalysisMetrics metrics, int ideaCount) {
    List<ContentIdeaEntity> ideas = new ArrayList<>();
    List<PostMetric> top = metrics.topPosts();

    for (int i = 0; i < ideaCount; i++) {
      ContentIdeaEntity idea = new ContentIdeaEntity();
      idea.setRequestId(requestId);
      idea.setSortOrder((short) (i + 1));

      if (!top.isEmpty() && i < top.size()) {
        PostMetric ref = top.get(i);
        idea.setTitle("Продолжение темы: «" + shorten(ref.title(), 50) + "» — но с конкретным кейсом");
        idea.setReason("Топ-пост набрал " + ref.views()
            + " просм. — аудитория уже доказала интерес к этой теме.");
        idea.setFormat(i == 0 ? "опрос" : "кейс");
      } else if (!metrics.workingTopics().isEmpty()) {
        TopicMetric topic = metrics.workingTopics().get(i % metrics.workingTopics().size());
        idea.setTitle("«" + topic.topic() + "»: одна ошибка, из-за которой теряют просмотры");
        idea.setReason("Тема даёт ~" + topic.avgViews() + " просм. — усильте конкретикой и цифрой в заголовке.");
        idea.setFormat("короткий");
      } else {
        idea.setTitle("Опрос: что вас бесит больше всего в [нише канала]?");
        idea.setReason("Интерактив собирает просмотры даже без реакций — люди хотят высказаться.");
        idea.setFormat("опрос");
      }

      idea.setSuggestedDay(switch (i % 3) {
        case 0 -> "Вторник";
        case 1 -> "Четверг";
        default -> "Суббота";
      });
      ideas.add(idea);
    }
    return ideas;
  }

  private static String defaultDay(AnalysisMetrics metrics) {
    String best = metrics.bestTimeSummary();
    if (best != null && !best.isBlank() && !"—".equals(best)) {
      int space = best.indexOf(' ');
      return space > 0 ? best.substring(0, space) : best;
    }
    return "Вторник";
  }

  private static String shorten(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  private static String cleanJson(String raw) {
    String trimmed = raw.trim();
    if (trimmed.startsWith("```")) {
      int start = trimmed.indexOf('{');
      int end = trimmed.lastIndexOf('}');
      if (start >= 0 && end > start) {
        return trimmed.substring(start, end + 1);
      }
    }
    return trimmed;
  }
}
