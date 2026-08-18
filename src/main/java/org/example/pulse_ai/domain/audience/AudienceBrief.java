package org.example.pulse_ai.domain.audience;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Сжатый профиль ЦА. searchQueries уже прогнаны через грунт (есть в постах).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AudienceBrief(
        String buyerRole,
        String jobToBeDone,
        List<String> evidenceTokens,
        List<String> searchQueries,
        List<String> parseVenues,
        String parseMethod,
        int minSubs,
        int maxSubs,
        int confidence,
        String source,
        String theme
) {
    public String queryLabel() {
        if (searchQueries == null || searchQueries.isEmpty()) {
            return "—";
        }
        return String.join(" · ", searchQueries);
    }

    public String summaryLine() {
        String role = buyerRole == null || buyerRole.isBlank() ? "роль не ясна" : buyerRole;
        String venues = parseVenues == null || parseVenues.isEmpty()
                ? ""
                : " · парсить: " + String.join("; ", parseVenues);
        return role + " · " + queryLabel() + venues;
    }

    public boolean usable() {
        return searchQueries != null && !searchQueries.isEmpty();
    }
}
