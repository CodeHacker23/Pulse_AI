package org.example.pulse_ai.stats;

import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.DailyViewsPoint;
import org.example.pulse_ai.stats.model.PostMetric;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnalyticsServiceTest {

    @Test
    void computesAverageViewsFromPosts() {
        AnalyticsService service = new AnalyticsService(null, null, properties(10));
        List<ChannelPostEntity> posts = List.of(
                post("Пост 1", 1000, "2026-06-01"),
                post("Пост 2", 3000, "2026-06-15"),
                post("Пост 3", 2000, "2026-06-20")
        );

        AnalysisMetrics metrics = service.analyzeFromPosts(null, posts, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertEquals(3, metrics.postCount());
        assertEquals(2000, metrics.avgViews());
        assertFalse(metrics.topPosts().isEmpty());
    }

    private static ChannelPostEntity post(String text, int views, String date) {
        ChannelPostEntity entity = new ChannelPostEntity();
        entity.setTelegramMessageId(text.hashCode());
        entity.setFullText(text);
        entity.setTextPreview(text);
        entity.setViews(views);
        entity.setReactionsTotal(50);
        entity.setEngagementRate(BigDecimal.valueOf(4.5));
        entity.setPublishedAt(LocalDate.parse(date).atStartOfDay(java.time.ZoneId.of("Europe/Moscow")).toInstant());
        return entity;
    }

    private static org.example.pulse_ai.config.PulseAnalysisProperties properties(int minPosts) {
        org.example.pulse_ai.config.PulseAnalysisProperties p = new org.example.pulse_ai.config.PulseAnalysisProperties();
        p.setMinPostsFull(minPosts);
        return p;
    }
}
