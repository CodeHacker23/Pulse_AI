package org.example.pulse_ai.domain.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.config.PulseProductChannelProperties;
import org.example.pulse_ai.persistence.entity.ProductStyleSnapshotEntity;
import org.example.pulse_ai.persistence.entity.ProductTrustedSourceEntity;
import org.example.pulse_ai.persistence.repository.ProductStyleSnapshotEntityRepository;
import org.example.pulse_ai.persistence.repository.ProductTrustedSourceRepository;
import org.example.pulse_ai.stats.scraper.ScrapedChannelPost;
import org.example.pulse_ai.stats.scraper.TelegramPublicChannelScraper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductStyleLearnerService {

    private static final String STYLE_SYSTEM = """
            Ты анализируешь стиль ведения Telegram-каналов для редактора Pulse AI.
            Выдай краткую выжимку (до 1200 символов):
            1) Тон и длина постов
            2) Какие форматы заходят (опросы, списки, истории)
            3) Как часто постят и в какое время (если видно)
            4) 3 приёма, которые можно адаптировать для канала про SMM-бота
            Без воды. По-русски.""";

    private final PulseProductChannelProperties properties;
    private final PulseAnalysisProperties analysisProperties;
    private final TelegramPublicChannelScraper scraper;
    private final LlmService llmService;
    private final ProductStyleSnapshotEntityRepository snapshotRepository;
    private final ProductTrustedSourceRepository trustedSourceRepository;

    @Transactional(readOnly = true)
    public String buildContextForPrompt() {
        StringBuilder ctx = new StringBuilder();
        ctx.append(ProductChannelPrompts.channelMissionBlock()).append("\n\n");

        snapshotRepository.findFirstByOrderByCreatedAtDesc().ifPresent(snap -> {
            ctx.append("📚 Выученный стиль референс-каналов (").append(snap.getCreatedAt()).append("):\n");
            ctx.append(snap.getSummary()).append("\n\n");
        });

        List<ProductTrustedSourceEntity> sources = trustedSourceRepository.findByActiveTrueOrderByTrustLevelDesc();
        if (!sources.isEmpty()) {
            ctx.append("✅ Проверенные источники (новости — только отсюда или общий инсайт без фактов):\n");
            for (ProductTrustedSourceEntity src : sources) {
                ctx.append("• ").append(src.getLabel()).append(" — ").append(src.getUrlOrUsername());
                if (src.getNotes() != null) {
                    ctx.append(" (").append(src.getNotes()).append(')');
                }
                ctx.append('\n');
            }
        }
        return ctx.toString().trim();
    }

    @Transactional
    public SyncResult syncFromReferences() {
        List<String> refs = properties.getReferenceChannels();
        if (refs == null || refs.isEmpty()) {
            return new SyncResult(false, "Добавьте pulse.product.reference-channels в конфиг.", 0);
        }

        StringBuilder samples = new StringBuilder();
        List<String> analyzed = new ArrayList<>();

        for (String username : refs) {
            String norm = username.replace("@", "").trim();
            if (norm.isBlank()) {
                continue;
            }
            try {
                List<ScrapedChannelPost> posts = scraper.fetchRecentPosts(norm, 12);
                if (posts.isEmpty()) {
                    continue;
                }
                analyzed.add("@" + norm);
                samples.append("\n=== @").append(norm).append(" ===\n");
                int n = 0;
                for (ScrapedChannelPost post : posts) {
                    if (n++ >= 6) {
                        break;
                    }
                    String text = post.text() != null ? post.text().replace('\n', ' ').trim() : "";
                    if (text.length() > 200) {
                        text = text.substring(0, 197) + "…";
                    }
                    samples.append("• ").append(text);
                    samples.append(" (").append(post.views()).append(" просм.)");
                    samples.append('\n');
                }
            } catch (Exception ex) {
                log.warn("Reference channel @{} scrape failed: {}", norm, ex.getMessage());
            }
        }

        if (analyzed.isEmpty()) {
            return new SyncResult(false, "Не удалось собрать посты с референс-каналов.", 0);
        }

        String summary;
        try {
            summary = llmService.completeTextWithTimeout(
                    STYLE_SYSTEM,
                    "Проанализируй стиль этих каналов:\n" + samples,
                    analysisProperties.getLlmTimeoutSeconds(),
                    1500
            );
        } catch (Exception ex) {
            log.warn("Style LLM failed: {}", ex.getMessage());
            summary = "Референсы: " + String.join(", ", analyzed) + ". Короткие посты с крючком в первой строке.";
        }

        ProductStyleSnapshotEntity snap = new ProductStyleSnapshotEntity();
        snap.setSummary(summary != null ? summary.trim() : "");
        snap.setReferenceList(String.join(", ", analyzed));
        snap.setPostSamples(samples.length() > 8000 ? samples.substring(0, 7997) + "…" : samples.toString());
        snapshotRepository.save(snap);

        log.info("Product style snapshot saved from {} channels", analyzed.size());
        return new SyncResult(true, "Стиль обновлён: " + analyzed.stream().collect(Collectors.joining(", ")), analyzed.size());
    }

    @Transactional
    public void ensureFreshStyle() {
        var latest = snapshotRepository.findFirstByOrderByCreatedAtDesc();
        if (latest.isPresent()
                && latest.get().getCreatedAt().isAfter(Instant.now().minus(7, ChronoUnit.DAYS))) {
            return;
        }
        if (properties.getReferenceChannels() != null && !properties.getReferenceChannels().isEmpty()) {
            syncFromReferences();
        }
    }

    public record SyncResult(boolean success, String message, int channelsAnalyzed) {
    }
}
