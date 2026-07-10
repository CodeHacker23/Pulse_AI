package org.example.pulse_ai.stats.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyViewsPoint(LocalDate date, int views, int posts) {
}
