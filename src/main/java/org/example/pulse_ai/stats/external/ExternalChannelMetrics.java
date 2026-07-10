package org.example.pulse_ai.stats.external;

/**
 * Метрики канала, собранные с внешней площадки (TGStat, Telemetr, Telega.in).
 * Любое поле может быть null, если площадка его не отдала.
 */
public record ExternalChannelMetrics(
        String source,
        boolean available,
        Integer subscribers,
        Integer avgReach,
        Double err,
        Double citationIndex,
        Long adPriceRub,
        String category,
        String note
) {
    public static ExternalChannelMetrics unavailable(String source, String note) {
        return new ExternalChannelMetrics(source, false, null, null, null, null, null, null, note);
    }
}
