package org.example.pulse_ai.domain.payment;

/** Тип пакета в каталоге: разборы / подписка ассистента / допы ЛС. */
public enum PackageKind {
    ANALYSIS,
    ASSISTANT,
    LS_TOPUP;

    public static PackageKind from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ANALYSIS;
        }
        try {
            return PackageKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ANALYSIS;
        }
    }
}
