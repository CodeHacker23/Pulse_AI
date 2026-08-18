package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.persistence.entity.ContentPlanItemEntity;
import org.example.pulse_ai.persistence.repository.ContentPlanItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ContentPlanService {

    private static final Set<String> EXCLUDE_STATUSES = Set.of("CHOSEN", "DRAFTED", "PUBLISHED");
    private static final int EXCLUDE_DAYS = 45;

    private final ContentPlanItemRepository repository;

    public static String topicKey(String title) {
        if (title == null) {
            return "";
        }
        String normalized = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }

    public List<String> excludedTitles(Long channelId) {
        Instant after = Instant.now().minus(EXCLUDE_DAYS, ChronoUnit.DAYS);
        return repository.findByChannelIdAndStatusInAndUpdatedAtAfter(channelId, EXCLUDE_STATUSES, after)
                .stream()
                .map(ContentPlanItemEntity::getTitle)
                .distinct()
                .limit(40)
                .toList();
    }

    public List<ContentPlanItemEntity> recentPlan(Long channelId) {
        return repository.findTop15ByChannelIdAndStatusInOrderByUpdatedAtDesc(
                channelId, EXCLUDE_STATUSES);
    }

    /**
     * Статусы идей канала (CHOSEN / DRAFTED / PUBLISHED) за окно exclude —
     * по ideaId и, если нет совпадения, по нормализованному title.
     */
    public Map<Long, String> statusesForIdeas(Long channelId, Collection<ContentIdeaEntity> ideas) {
        Map<Long, String> out = new HashMap<>();
        if (channelId == null || ideas == null || ideas.isEmpty()) {
            return out;
        }
        Instant after = Instant.now().minus(EXCLUDE_DAYS, ChronoUnit.DAYS);
        List<ContentPlanItemEntity> recent = repository.findByChannelIdAndStatusInAndUpdatedAtAfter(
                channelId, EXCLUDE_STATUSES, after);
        Map<Long, String> byIdeaId = new HashMap<>();
        Map<String, String> byTopic = new HashMap<>();
        for (ContentPlanItemEntity item : recent) {
            if (item.getIdeaId() != null) {
                byIdeaId.merge(item.getIdeaId(), item.getStatus(), ContentPlanService::preferStatus);
            }
            if (item.getTopicKey() != null && !item.getTopicKey().isBlank()) {
                byTopic.merge(item.getTopicKey(), item.getStatus(), ContentPlanService::preferStatus);
            }
        }
        for (ContentIdeaEntity idea : ideas) {
            if (idea == null || idea.getId() == null) {
                continue;
            }
            String status = byIdeaId.get(idea.getId());
            if (status == null) {
                status = byTopic.get(topicKey(idea.getTitle()));
            }
            if (status != null) {
                out.put(idea.getId(), status);
            }
        }
        return out;
    }

    private static String preferStatus(String a, String b) {
        return rank(b) >= rank(a) ? b : a;
    }

    @Transactional
    public void markChosen(Long channelId, ContentIdeaEntity idea, Long requestId) {
        upsert(channelId, idea.getTitle(), idea.getId(), requestId, "CHOSEN");
    }

    @Transactional
    public void markDrafted(Long channelId, ContentIdeaEntity idea, Long requestId) {
        upsert(channelId, idea.getTitle(), idea.getId(), requestId, "DRAFTED");
    }

    @Transactional
    public void markPublished(Long channelId, Long ideaId, String title, Long requestId) {
        if (ideaId != null) {
            repository.findFirstByChannelIdAndIdeaId(channelId, ideaId).ifPresentOrElse(item -> {
                item.setStatus("PUBLISHED");
                if (title != null && !title.isBlank()) {
                    item.setTitle(title);
                    item.setTopicKey(topicKey(title));
                }
                repository.save(item);
            }, () -> upsert(channelId, title != null ? title : "post", ideaId, requestId, "PUBLISHED"));
            return;
        }
        if (title != null && !title.isBlank()) {
            upsert(channelId, title, null, requestId, "PUBLISHED");
        }
    }

    public String formatPlanSummary(Long channelId) {
        List<ContentPlanItemEntity> items = recentPlan(channelId);
        if (items.isEmpty()) {
            return "📋 <b>План контента</b>\nПока пусто — выберите идею или опубликуйте пост.";
        }
        StringBuilder sb = new StringBuilder("📋 <b>План контента</b> (выбранное / опубликовано)\n\n");
        for (ContentPlanItemEntity item : items.stream().limit(10).toList()) {
            sb.append("• ").append(statusLabel(item.getStatus())).append(' ')
                    .append(esc(item.getTitle())).append('\n');
        }
        return sb.toString().trim();
    }

    /** Сигнал Ad Radar → черновая тема в контент-план (SUGGESTED). */
    @Transactional
    public void suggestFromRadar(Long channelId, String snippet, String keyword) {
        if (channelId == null || snippet == null || snippet.isBlank()) {
            return;
        }
        String title = "Тренд из чата" + (keyword != null ? " («" + keyword + "»)" : "")
                + ": " + snippet.trim();
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        String key = topicKey(title);
        if (repository.findFirstByChannelIdAndTopicKeyOrderByUpdatedAtDesc(channelId, key).isPresent()) {
            return;
        }
        ContentPlanItemEntity item = new ContentPlanItemEntity();
        item.setChannelId(channelId);
        item.setTitle(title);
        item.setTopicKey(key.isBlank() ? "radar-" + System.currentTimeMillis() : key);
        item.setStatus("SUGGESTED");
        repository.save(item);
    }

    private void upsert(Long channelId, String title, Long ideaId, Long requestId, String status) {
        String key = topicKey(title);
        ContentPlanItemEntity item = null;
        if (ideaId != null) {
            item = repository.findFirstByChannelIdAndIdeaId(channelId, ideaId).orElse(null);
        }
        if (item == null && !key.isBlank()) {
            item = repository.findFirstByChannelIdAndTopicKeyOrderByUpdatedAtDesc(channelId, key).orElse(null);
        }
        if (item == null) {
            item = new ContentPlanItemEntity();
            item.setChannelId(channelId);
        }
        item.setTitle(title);
        item.setTopicKey(key.isBlank() ? "untitled" : key);
        item.setIdeaId(ideaId);
        item.setSourceRequestId(requestId);
        item.setStatus(rankStatus(item.getStatus(), status));
        repository.save(item);
    }

    private static String rankStatus(String current, String next) {
        int cur = rank(current);
        int n = rank(next);
        return n >= cur ? next : (current != null ? current : next);
    }

    private static int rank(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case "SUGGESTED" -> 1;
            case "CHOSEN" -> 2;
            case "DRAFTED" -> 3;
            case "PUBLISHED" -> 4;
            default -> 0;
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "CHOSEN" -> "📌";
            case "DRAFTED" -> "✍️";
            case "PUBLISHED" -> "✅";
            default -> "•";
        };
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
