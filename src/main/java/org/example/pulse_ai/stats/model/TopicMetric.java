package org.example.pulse_ai.stats.model;

import java.math.BigDecimal;

public record TopicMetric(String topic, BigDecimal avgEngagementRate, int postCount, int avgViews) {
}
