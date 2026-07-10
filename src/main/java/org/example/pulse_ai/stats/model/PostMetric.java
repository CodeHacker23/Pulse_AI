package org.example.pulse_ai.stats.model;

import java.math.BigDecimal;

public record PostMetric(
        int messageId,
        String title,
        int views,
        int reactions,
        BigDecimal engagementRate,
        String failureReason
) {
}
