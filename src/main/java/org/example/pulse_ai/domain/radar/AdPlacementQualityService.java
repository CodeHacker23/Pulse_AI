package org.example.pulse_ai.domain.radar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.channel.ChannelConnectException;
import org.example.pulse_ai.domain.channel.ChannelService;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.example.pulse_ai.persistence.repository.ChannelPostRepository;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.AnalyticsService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdPlacementQualityService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final Pattern AD_MARKERS = Pattern.compile(
            "(?i)(реклам|#ad\\b|партн[её]р|pr\\s|размещени|прайс|промокод|скидк)");

    private final ChannelService channelService;
    private final AnalyticsService analyticsService;
    private final ChannelPostRepository channelPostRepository;

    public QualityReport scorePublicChannel(String rawInput) {
        ChannelEntity channel;
        try {
            channel = channelService.resolveForComparison(rawInput);
        } catch (ChannelConnectException ex) {
            return QualityReport.failed(ex.getMessage());
        } catch (Exception ex) {
            log.warn("Placement quality check failed: {}", ex.getMessage());
            return QualityReport.failed("Не удалось проверить канал. Убедитесь, что он публичный.");
        }

        LocalDate to = LocalDate.now(MSK);
        LocalDate from = to.minusDays(90);
        AnalysisMetrics metrics = analyticsService.analyze(channel.getId(), from, to);

        List<ChannelPostEntity> recent = channelPostRepository
                .findByChannelIdAndPublishedAtBetweenOrderByPublishedAtAsc(
                        channel.getId(),
                        to.minusDays(30).atStartOfDay(MSK).toInstant(),
                        to.plusDays(1).atStartOfDay(MSK).toInstant());

        int posts30 = recent.size();
        int adPosts = 0;
        for (ChannelPostEntity post : recent) {
            String text = post.getFullText() != null ? post.getFullText() : post.getTextPreview();
            if (text == null) {
                text = "";
            }
            if (AD_MARKERS.matcher(text).find()) {
                adPosts++;
            }
        }
        int adRatio = posts30 > 0 ? (adPosts * 100 / posts30) : 0;

        Instant lastPostAt = recent.isEmpty()
                ? null
                : recent.get(recent.size() - 1).getPublishedAt();
        long daysSinceLast = lastPostAt == null
                ? 999
                : ChronoUnit.DAYS.between(lastPostAt.atZone(MSK).toLocalDate(), to);

        String verdict;
        int score;
        String notes;

        if (posts30 == 0 && daysSinceLast > 30) {
            verdict = "DEAD";
            score = 5;
            notes = "Нет постов 30+ дней — канал выглядит брошенным.";
        } else if (adRatio >= 60 && posts30 >= 5) {
            verdict = "SPAM_HEAVY";
            score = 15;
            notes = "Больше " + adRatio + "% постов похожи на рекламу — аудитория может быть выжженной.";
        } else if (posts30 < 2 && daysSinceLast > 14) {
            verdict = "LOW";
            score = 25;
            notes = "Мало активности: " + posts30 + " пост(ов) за 30 дней.";
        } else if (metrics.avgViews() > 0
                && metrics.avgEngagementRate().compareTo(BigDecimal.valueOf(1.5)) >= 0) {
            verdict = "ALIVE";
            score = Math.min(95, 50 + Math.min(30, metrics.avgViews() / 100) + (100 - adRatio) / 5);
            notes = "Канал живой: ~" + metrics.avgViews() + " просм./пост, рекламы ~" + adRatio + "%.";
        } else {
            verdict = "ALIVE";
            score = Math.max(35, 60 - adRatio / 2);
            notes = "Есть публикации (" + posts30 + " за 30 дн.), рекламы ~" + adRatio + "%.";
        }

        return new QualityReport(
                true,
                channel.getId(),
                channel.getUsername(),
                channel.getTitle(),
                verdict,
                (short) score,
                notes,
                posts30,
                (short) adRatio,
                metrics.avgViews(),
                null);
    }

    public static String verdictLabel(String verdict) {
        return switch (verdict != null ? verdict : "UNKNOWN") {
            case "ALIVE" -> "✅ Живой";
            case "DEAD" -> "💀 Мёртвый";
            case "LOW" -> "⚠️ Слабая активность";
            case "SPAM_HEAVY" -> "🚫 Много рекламы";
            default -> "❓ Не проверен";
        };
    }

    public record QualityReport(
            boolean ok,
            Long scrapedChannelId,
            String username,
            String title,
            String verdict,
            short score,
            String notes,
            int postsLast30d,
            short adRatioPercent,
            int avgViews,
            String error
    ) {
        public static QualityReport failed(String error) {
            return new QualityReport(false, null, null, null, "UNKNOWN", (short) 0,
                    null, 0, (short) 0, 0, error);
        }
    }
}
