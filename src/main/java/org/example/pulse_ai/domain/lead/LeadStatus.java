package org.example.pulse_ai.domain.lead;

/** CRM-статус горячего лида: мини-воронка от нового обращения до продажи. */
public enum LeadStatus {
    NEW("🆕 Новый"),
    IN_PROGRESS("📞 В работе"),
    WON("✅ Продажа"),
    LOST("❌ Слив");

    private final String label;

    LeadStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static LeadStatus of(String raw) {
        if (raw == null || raw.isBlank()) {
            return NEW;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NEW;
        }
    }
}
