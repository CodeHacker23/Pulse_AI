package org.example.pulse_ai.domain.radar;

/** Статусы рекламной сделки. */
public final class AdDealStatuses {

    public static final String INTEREST = "INTEREST";
    public static final String BRIEF = "BRIEF";
    public static final String AWAITING_ADMIN = "AWAITING_ADMIN";
    public static final String AGREED = "AGREED";
    public static final String REJECTED = "REJECTED";
    public static final String PAID = "PAID";
    public static final String LIVE = "LIVE";
    public static final String DONE = "DONE";

    private AdDealStatuses() {
    }

    public static String label(String status) {
        return switch (status != null ? status : "") {
            case INTEREST -> "интерес";
            case BRIEF -> "бриф готов";
            case AWAITING_ADMIN -> "ждём админа";
            case AGREED -> "согласовано";
            case REJECTED -> "отказ";
            case PAID -> "оплачено";
            case LIVE -> "в эфире";
            case DONE -> "готово";
            default -> status != null ? status : "—";
        };
    }

    public static boolean isOpen(String status) {
        return INTEREST.equals(status)
                || BRIEF.equals(status)
                || AWAITING_ADMIN.equals(status)
                || AGREED.equals(status);
    }
}
