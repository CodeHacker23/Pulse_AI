package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.billing")
public class PulseBillingProperties {

    /** false = всё бесплатно (режим разработки) */
    private boolean enabled = false;

    private int freeIdeasCount = 3;
    private int paidIdeasCount = 12;
    private int freeDraftLimit = 3;
    private int paidDraftLimit = 7;
    /** Сколько раз можно обновить пул идей в рамках одного запроса (без списания баланса). */
    private int freeIdeasRegenLimit = 1;
    private int paidIdeasRegenLimit = 2;

    public int ideasFor(org.example.pulse_ai.domain.request.RequestType type) {
        if (!enabled) {
            return paidIdeasCount;
        }
        return type == org.example.pulse_ai.domain.request.RequestType.FREE
                ? freeIdeasCount
                : paidIdeasCount;
    }

    public int draftLimitFor(org.example.pulse_ai.domain.request.RequestType type) {
        if (!enabled) {
            return paidDraftLimit;
        }
        return type == org.example.pulse_ai.domain.request.RequestType.FREE
                ? freeDraftLimit
                : paidDraftLimit;
    }

    public int ideasRegenLimitFor(org.example.pulse_ai.domain.request.RequestType type) {
        if (!enabled) {
            return paidIdeasRegenLimit;
        }
        return type == org.example.pulse_ai.domain.request.RequestType.FREE
                ? freeIdeasRegenLimit
                : paidIdeasRegenLimit;
    }
}
