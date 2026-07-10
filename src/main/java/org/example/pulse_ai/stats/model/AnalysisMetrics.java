package org.example.pulse_ai.stats.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AnalysisMetrics(
        int postCount,
        int avgViews,
        BigDecimal avgEngagementRate,
        BigDecimal viewsDeltaPercent,
        List<DailyViewsPoint> dailyViews,
        List<PostMetric> topPosts,
        List<PostMetric> worstPosts,
        List<PublishSlotMetric> bestSlots,
        List<PublishSlotMetric> avoidSlots,
        List<TopicMetric> workingTopics,
        String bestTimeSummary,
        String frequencyRecommendation,
        boolean limitedAnalysis
) {
}
