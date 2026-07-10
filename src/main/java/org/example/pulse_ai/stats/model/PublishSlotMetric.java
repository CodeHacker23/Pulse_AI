package org.example.pulse_ai.stats.model;

import java.math.BigDecimal;

public record PublishSlotMetric(String day, String time, BigDecimal engagementRate, int avgViews) {
}
