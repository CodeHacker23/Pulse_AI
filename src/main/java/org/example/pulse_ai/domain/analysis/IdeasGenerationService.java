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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdeasGenerationService {

  private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
  private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private static final String SYSTEM_PROMPT = """
      Ты — копирайтер ЭТОГО Telegram-канала. Пишешь как человек из его команды, не «нейросеть».
      Заголовки (title) — первая строка поста в ленте: по ней решают, открыть или пролистать.
      Если в запросе есть бриф разбора — закрывай просадки и шаги роста в первую очередь.

      Перед генерацией мысленно зафиксируй 2–3 фразы голоса канала из примеров — в ответ их НЕ выводи.
      Ответ — ТОЛЬКО валидный JSON, без markdown и без пояснений вне JSON.""";

  private final LlmService llmService;
  private final ObjectMapper objectMapper;

  public List<ContentIdeaEntity> generateIdeas(
      Long requestId,
      String channelTitle,
      AnalysisMetrics metrics,
      int ideaCount
  ) {
    return generateIdeas(requestId, channelTitle, metrics, ideaCount, 60, List.of());
  }

  public List<ContentIdeaEntity> generateIdeas(
      Long requestId,
      String channelTitle,
      AnalysisMetrics metrics,
      int ideaCount,
      int timeoutSeconds
  ) {
    return generateIdeas(requestId, channelTitle, metrics, ideaCount, timeoutSeconds, List.of());
  }

  public List<ContentIdeaEntity> generateIdeas(
      Long requestId,
      String channelTitle,
      AnalysisMetrics metrics,
      int ideaCount,
      int timeoutSeconds,
      List<String> excludeTitles
  ) {
    return generateIdeas(requestId, channelTitle, metrics, ideaCount, timeoutSeconds, excludeTitles, null);
  }

  public List<ContentIdeaEntity> generateIdeas(
      Long requestId,
      String channelTitle,
      AnalysisMetrics metrics,
      int ideaCount,
      int timeoutSeconds,
      List<String> excludeTitles,
      String analysisBrief
  ) {
    try {
      String userPrompt = buildPrompt(channelTitle, metrics, ideaCount, excludeTitles, analysisBrief);
      String json = llmService.completeJsonWithTimeout(
          SYSTEM_PROMPT, userPrompt, timeoutSeconds);
      return parseIdeas(requestId, json, ideaCount, metrics, excludeTitles);
    } catch (Exception ex) {
      log.warn("LLM ideas failed ({}s), using fallback: {}", timeoutSeconds, ex.getMessage());
      return fallbackIdeas(requestId, metrics, ideaCount);
    }
  }

  private String buildPrompt(
      String channelTitle,
      AnalysisMetrics metrics,
      int ideaCount,
      List<String> excludeTitles,
      String analysisBrief
  ) {
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

    StringBuilder excluded = new StringBuilder();
    if (excludeTitles != null) {
      for (String t : excludeTitles) {
        if (t != null && !t.isBlank()) {
          excluded.append("• «").append(t.trim()).append("»\n");
        }
      }
    }
    if (excluded.isEmpty()) {
      excluded.append("— нет\n");
    }

    String brief = analysisBrief != null && !analysisBrief.isBlank()
        ? analysisBrief.trim()
        : "— брифа нет: ориентируйся на голос канала и рабочие темы";

    String today = LocalDate.now(MSK).format(DAY_FMT);

    return """
        Канал: «%s»

        ЗАДАЧА
        Придумай ровно %d идей постов на ближайшие 7–14 дней (текущая дата: %s).
        Каждая идея — под стиль, интонацию и ЦА ИМЕННО ЭТОГО канала.

        ШАГ 0 (мысленно, в JSON не пиши)
        Зафиксируй 2–3 характерные фразы/интонации из «голоса канала» ниже.
        Дальше пиши строго в этом ключе; перед выводом сверь каждый title с этим голосом.

        ИСКЛЮЧИТЬ (уже в контент-плане — тему не повторять и не перефразировать близко):
        %s

        ГОЛОС КАНАЛА (формулировки топ-постов; темы не копируй):
        %s

        ЧТО НЕ ЗАШЛО (не повторять тон / форму / структуру заголовка):
        %s

        РАБОЧИЕ ТЕМЫ НИШИ:
        %s

        КОНТЕКСТ: %d постов в разборе%s, лучшее время: %s

        БРИФ РАЗБОРА (просадки и шаги роста — закрывай их идеями):
        %s

        ПРАВИЛА ДЛЯ TITLE
        - Готовый заголовок / первая строка поста, цепляет уже в списке идей.
        - До 90 символов.
        - Не повторяй одну синтаксическую конструкцию дважды подряд.
        - Крючок не обманывает: пост логически раскроет обещание title.
        - Чередуй ТИПЫ крючков: личная история / цифра-факт / вопрос-провокация / антитеза «раньше→теперь» / признание ошибки.
        - Примеры ниже — только про ТИП крючка, не копируй их синтаксис.

        ПЛОХИЕ TITLE:
        ✗ «Смешная история про вашу нишу»
        ✗ «5 советов как улучшить контент»
        ✗ «Почему важно развивать канал в 2026»
        ✗ «Разберём главные тренды отрасли»

        СИЛЬНЫЕ TITLE (адаптируй под нишу и голос):
        ✓ «Потратил 40 часов на X — ошибка оказалась в одной строчке»
        ✓ «Опрос: сколько вы реально тратите на Y? (честно)»
        ✓ «Клиент попросил Z. Показываю, что вышло — без прикрас»
        ✓ «Мне 3 года говорили «делай так». Перестал — просмотры выросли»

        ФОРМАТЫ И БАЛАНС
        format: longread | короткий | опрос | кейс
        Длины при черновике: короткий ≤400; кейс/средний 600–900; longread 800–1000 (не 1500 — лимит Telegram + фото).
        %s

        ДНИ
        suggested_day — день недели на русском. Если идей ≤ 7 — без повторов дня.

        ПОЛЯ
        - closes_gap: какую просадку из брифа закрывает (напр. «скучные первые строки», «однообразие форматов»). Нет брифа → «н/д».
        - reason: одно предложение с конкретной эмоцией/болью ЦА (страх, жадность, узнавание, любопытство…) — не «это интересно аудитории».
        - cta: ориентир финала. Примерно у половины идей — живой вопрос аудитории по теме
          (не штамп «а вы?», «согласны?», «пишите в комменты»). У остальных — утверждение / punchline без вопроса.

        Верни JSON:
        {
          "ideas": [
            {
              "title": "string",
              "reason": "string",
              "format": "string",
              "suggested_day": "string",
              "closes_gap": "string",
              "cta": "string"
            }
          ]
        }
        """.formatted(
        channelTitle,
        ideaCount,
        today,
        excluded.toString().trim(),
        topPosts.toString().trim(),
        weakPosts.toString().trim(),
        topics.toString().trim(),
        metrics.postCount(),
        metrics.avgViews() > 0 ? (", ~" + metrics.avgViews() + " просм. в среднем") : "",
        metrics.bestTimeSummary(),
        brief,
        formatBalanceRules(ideaCount)
    );
  }

  private static String formatBalanceRules(int n) {
    if (n <= 3) {
      return "Из " + n + " идей: все форматы разные, без повторов.";
    }
    if (n <= 6) {
      return "Из " + n + " идей: минимум 1 кейс и 1 опрос; не более 2 одинаковых форматов подряд.";
    }
    return "Из " + n + " идей: минимум 2 кейса и 1 опрос; longread/короткий чередуй — не более 2 одинаковых подряд.";
  }

  private List<ContentIdeaEntity> parseIdeas(
      Long requestId, String json, int ideaCount, AnalysisMetrics metrics, List<String> excludeTitles
  ) throws Exception {
    Set<String> excludeKeys = new java.util.HashSet<>();
    if (excludeTitles != null) {
      for (String t : excludeTitles) {
        excludeKeys.add(ContentPlanService.topicKey(t));
      }
    }
    JsonNode root = objectMapper.readTree(cleanJson(json));
    JsonNode ideasNode = root.path("ideas");
    List<ContentIdeaEntity> ideas = new ArrayList<>();
    for (int i = 0; i < ideasNode.size() && ideas.size() < ideaCount; i++) {
      JsonNode node = ideasNode.get(i);
      String title = node.path("title").asText("").trim();
      if (title.isBlank() || looksGeneric(title)) {
        continue;
      }
      if (excludeKeys.contains(ContentPlanService.topicKey(title))) {
        continue;
      }
      ContentIdeaEntity idea = new ContentIdeaEntity();
      idea.setRequestId(requestId);
      idea.setSortOrder((short) (ideas.size() + 1));
      idea.setTitle(title.length() > 90 ? title.substring(0, 87) + "…" : title);
      idea.setReason(node.path("reason").asText("Цепляет на боли аудитории канала."));
      idea.setFormat(normalizeFormat(node.path("format").asText("короткий")));
      idea.setSuggestedDay(node.path("suggested_day").asText(defaultDay(metrics)));
      String gap = node.path("closes_gap").asText("").trim();
      idea.setClosesGap(gap.isBlank() ? "н/д" : shorten(gap, 240));
      String cta = node.path("cta").asText("").trim();
      idea.setCta(cta.isBlank() ? null : shorten(cta, 500));
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

  private static String normalizeFormat(String raw) {
    if (raw == null || raw.isBlank()) {
      return "короткий";
    }
    String t = raw.toLowerCase(Locale.ROOT);
    if (t.contains("опрос") || t.contains("poll")) {
      return "опрос";
    }
    if (t.contains("кейс") || t.contains("case")) {
      return "кейс";
    }
    if (t.contains("long") || t.contains("длин")) {
      return "longread";
    }
    return "короткий";
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
        idea.setClosesGap("н/д");
        idea.setCta("А у вас так было? Напишите в комментарии.");
      } else if (!metrics.workingTopics().isEmpty()) {
        TopicMetric topic = metrics.workingTopics().get(i % metrics.workingTopics().size());
        idea.setTitle("«" + topic.topic() + "»: одна ошибка, из-за которой теряют просмотры");
        idea.setReason("Тема даёт ~" + topic.avgViews() + " просм. — усильте конкретикой и цифрой в заголовке.");
        idea.setFormat("короткий");
        idea.setClosesGap("н/д");
        idea.setCta("Согласны? Плюсаните в комментариях.");
      } else {
        idea.setTitle("Опрос: что вас бесит больше всего в [нише канала]?");
        idea.setReason("Интерактив собирает просмотры даже без реакций — люди хотят высказаться.");
        idea.setFormat("опрос");
        idea.setClosesGap("н/д");
        idea.setCta("Голосуйте и допишите свой вариант.");
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
