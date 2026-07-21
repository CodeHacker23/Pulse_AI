package org.example.pulse_ai.domain.lead;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.persistence.entity.HotLeadEntity;
import org.example.pulse_ai.persistence.entity.SalesLearningEntity;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.SalesLearningRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * После won/lost пишет короткий читаемый вывод — ты читаешь, агент подмешивает в следующие ответы.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesLearningService {

    private static final String SYSTEM = """
            Ты — аналитик переговоров в Telegram. По исходу лида сформулируй 1–2 коротких вывода для продавца.
            Без воды. Формат:
            • что сработало / что нет
            • какая формулировка на будущее (если уместно)
            Только текст, без JSON.""";

    private final SalesLearningRepository learningRepository;
    private final ChannelRepository channelRepository;
    private final LlmService llmService;

    @Transactional
    public SalesLearningEntity recordOutcome(HotLeadEntity lead, String outcome) {
        String summary = buildSummary(lead, outcome);
        SalesLearningEntity row = new SalesLearningEntity();
        row.setChannelId(lead.getChannelId());
        row.setOwnerUserId(lead.getOwnerUserId());
        row.setLeadId(lead.getId());
        row.setOutcome(outcome);
        row.setSummary(summary.length() > 1500 ? summary.substring(0, 1497) + "…" : summary);
        SalesLearningEntity saved = learningRepository.save(row);

        // Удачные формулировки при WON — мягко дополняем книгу возражений канала.
        if ("WON".equalsIgnoreCase(outcome) && lead.getSuggestedReply() != null
                && !lead.getSuggestedReply().isBlank()) {
            appendWonPhrase(lead.getChannelId(), lead.getCommentText(), lead.getSuggestedReply());
        }
        return saved;
    }

    public List<SalesLearningEntity> latestForOwner(long ownerUserId) {
        return learningRepository.findTop10ByOwnerUserIdOrderByCreatedAtDesc(ownerUserId);
    }

    public String recentContextForChannel(long channelId) {
        List<SalesLearningEntity> rows = learningRepository.findTop5ByChannelIdOrderByCreatedAtDesc(channelId);
        if (rows.isEmpty()) {
            return "";
        }
        return rows.stream()
                .map(r -> "[" + r.getOutcome() + "] " + r.getSummary())
                .collect(Collectors.joining("\n"));
    }

    private String buildSummary(HotLeadEntity lead, String outcome) {
        try {
            String prompt = """
                    Исход: %s
                    Комментарий клиента: «%s»
                    Черновик/ответ: «%s»
                    Категория лида: %s
                    """.formatted(
                    outcome,
                    nullToDash(lead.getCommentText()),
                    nullToDash(lead.getSuggestedReply()),
                    nullToDash(lead.getCategory())
            );
            String text = llmService.completeTextWithTimeout(SYSTEM, prompt, 25, 220);
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        } catch (Exception ex) {
            log.warn("Learning LLM failed: {}", ex.getMessage());
        }
        return fallbackSummary(lead, outcome);
    }

    private static String fallbackSummary(HotLeadEntity lead, String outcome) {
        if ("WON".equalsIgnoreCase(outcome)) {
            return "Сделка закрыта. Клиент писал: «" + shortText(lead.getCommentText())
                    + "». Зафиксируйте, какая фраза дожала.";
        }
        return "Слив. Клиент: «" + shortText(lead.getCommentText())
                + "». Проверьте, не ушли ли за рамки оффера или не давили слишком рано.";
    }

    private void appendWonPhrase(Long channelId, String comment, String reply) {
        channelRepository.findById(channelId).ifPresent(ch -> {
            String line = "• На «" + shortText(comment) + "» зашло: «" + shortText(reply) + "»";
            String current = ch.getSalesObjections();
            if (current != null && current.contains(line)) {
                return;
            }
            String next = (current == null || current.isBlank()) ? line : current.trim() + "\n" + line;
            if (next.length() > 4000) {
                next = next.substring(next.length() - 4000);
            }
            ch.setSalesObjections(next);
            channelRepository.save(ch);
        });
    }

    private static String shortText(String s) {
        if (s == null) {
            return "—";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 80 ? t.substring(0, 77) + "…" : t;
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s.replace("\"", "'");
    }
}
