package org.example.pulse_ai.domain.lead;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Определяет «горячий лид» в комментарии под постом: есть ли намерение купить/узнать цену.
 * Дешёвый предфильтр по ключевым словам, затем LLM подтверждает и классифицирует.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadDetectionService {

    private static final int TIMEOUT_SECONDS = 20;

    // Быстрый предфильтр: сигналы покупательского интереса.
    private static final List<String> HINTS = List.of(
            "цен", "стоит", "стоим", "сколько", "почём", "почем", "прайс", "ценник",
            "купить", "куплю", "заказ", "оформить", "оплат", "реквизит", "приобрес",
            "как взять", "хочу", "беру", "запиш", "записаться", "куда плат", "в лс", "в личку",
            "дорого", "дешев", "рассрочк", "предоплат", "доставк", "есть в налич", "актуально",
            "у конкурент", "подум", "не уверен", "дороговат"
    );

    private static final String SYSTEM = """
            Ты — детектор горячих лидов в комментариях Telegram-канала. По тексту комментария реши,
            это ли потенциальный клиент с намерением купить/узнать цену/выйти на контакт для покупки
            ИЛИ возражение к покупке (дорого, подумаю, у конкурента дешевле).

            Горячий лид (hot=true): спрашивает цену, «сколько стоит», «как купить/заказать/оплатить»,
            просит реквизиты/ссылку/в личку по покупке, выражает намерение купить,
            или возражение по цене/сравнению (всё ещё hot — это шанс дожать).
            НЕ лид (hot=false): общий вопрос, спор, эмоция, флуд, благодарность, оффтоп, спам.

            Категории: "price", "buy", "contact", "objection", "other".
            Ответ строго JSON: {"hot": true|false, "category": "...", "reason": "коротко по-русски"}""";

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public boolean prefilter(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return HINTS.stream().anyMatch(lower::contains);
    }

    public LeadVerdict classify(String comment) {
        if (!prefilter(comment)) {
            return LeadVerdict.cold();
        }
        try {
            String prompt = "Комментарий: \"" + comment.replace("\"", "'") + "\"";
            String json = llmService.completeJsonWithTimeout(SYSTEM, prompt, TIMEOUT_SECONDS);
            JsonNode root = objectMapper.readTree(clean(json));
            boolean hot = root.path("hot").asBoolean(false);
            String category = root.path("category").asText("other");
            String reason = root.path("reason").asText("");
            return new LeadVerdict(hot, category, reason);
        } catch (Exception ex) {
            log.warn("Lead classify failed: {}", ex.getMessage());
            // Предфильтр уже сработал — считаем лидом, чтобы не терять клиента.
            return new LeadVerdict(true, "price", "по ключевым словам о цене/покупке");
        }
    }

    private static final String REPLY_SYSTEM_GROUNDED = """
            Ты — живой менеджер в комментариях Telegram. Пиши КАК ЧЕЛОВЕК в мессенджере, не как нейросеть.

            РЕЧЬ:
            • 1–3 коротких предложения ИЛИ 2–3 коротких абзаца (как отдельные реплики). Максимум ~350 знаков.
            • Просторечие ок: «да», «могу в лс», «если кратко». Без канцелярита и воды.
            • Запрещено: «конечно!», «отличный вопрос», «в современном мире», «давайте разберём», простыни, списки из 5+ пунктов, эмодзи-спам.
            • Всегда один следующий шаг: вопрос / «напишите в лс» / конкретика из базы.

            ФАКТЫ:
            • Только из «Базы знаний» и «Книги возражений». Ничего не выдумывай (цены, сроки, гарантии).
            • Стоп-темы и «чего НЕ обещать» — священны.
            • Если факта нет — не сочиняй, зови в личку по контакту из базы.
            Только текст ответа клиенту.""";

    private static final String REPLY_SYSTEM_SAFE = """
            Ты — живой менеджер в Telegram-комментариях. Базы компании НЕТ.
            • Не называй цены/сроки/условия — ничего не выдумывай.
            • 1–2 коротких предложения, по-человечески: интерес ок, детали — в личку.
            • Без канцелярита и простыней. Только текст ответа.""";

    /**
     * Черновик ответа: факты компании + возражения + недавние выводы + живая речь.
     */
    public String suggestReply(
            String comment,
            String channelTitle,
            String knowledgeBase,
            String objectionsBook,
            String recentLearnings
    ) {
        boolean grounded = knowledgeBase != null && !knowledgeBase.isBlank();
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("Канал: ").append(channelTitle == null ? "" : channelTitle).append('\n');
            if (grounded) {
                prompt.append("База знаний компании (единственный источник фактов):\n")
                        .append(knowledgeBase.trim()).append('\n');
            }
            if (objectionsBook != null && !objectionsBook.isBlank()) {
                prompt.append("Книга возражений / удачные формулировки:\n")
                        .append(objectionsBook.trim()).append('\n');
            }
            if (recentLearnings != null && !recentLearnings.isBlank()) {
                prompt.append("Недавние выводы по переговорам (учти, не цитируй дословно клиенту):\n")
                        .append(recentLearnings.trim()).append('\n');
            }
            prompt.append("Комментарий клиента: \"").append(comment.replace("\"", "'")).append("\"\n");
            prompt.append("Напиши короткий ответ этому клиенту.");
            String system = grounded ? REPLY_SYSTEM_GROUNDED : REPLY_SYSTEM_SAFE;
            String reply = llmService.completeTextWithTimeout(system, prompt.toString(), TIMEOUT_SECONDS, 280);
            if (reply == null) {
                return null;
            }
            reply = reply.trim();
            return reply.isBlank() ? null : reply;
        } catch (Exception ex) {
            log.warn("Suggest reply failed: {}", ex.getMessage());
            return null;
        }
    }

    /** Совместимость: без книги возражений. */
    public String suggestReply(String comment, String channelTitle, String knowledgeBase) {
        return suggestReply(comment, channelTitle, knowledgeBase, null, null);
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

    public record LeadVerdict(boolean hot, String category, String reason) {
        public static LeadVerdict cold() {
            return new LeadVerdict(false, "other", "");
        }

        public String categoryLabel() {
            return switch (category == null ? "" : category) {
                case "objection" -> "⚡ Возражение (цена/сравнение)";
                case "price" -> "💰 Спрашивает цену";
                case "buy" -> "🛒 Готов купить";
                case "contact" -> "📩 Просит контакт";
                default -> "🔥 Интерес к покупке";
            };
        }
    }
}
