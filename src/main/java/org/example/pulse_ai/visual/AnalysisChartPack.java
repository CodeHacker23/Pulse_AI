package org.example.pulse_ai.visual;

import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.DailyViewsPoint;
import org.example.pulse_ai.stats.model.PostMetric;
import org.example.pulse_ai.stats.model.PublishSlotMetric;
import org.example.pulse_ai.stats.model.TopicMetric;

import java.util.Map;

public record AnalysisChartPack(
        byte[] dashboard,
        byte[] viewsTrend,
        byte[] engagementHeatmap,
        byte[] topPosts,
        byte[] topics
) {
    public Map<String, byte[]> asMap() {
        return Map.of(
                "dashboard", dashboard,
                "views", viewsTrend,
                "engagement", engagementHeatmap,
                "topPosts", topPosts,
                "topics", topics
        );
    }
}
