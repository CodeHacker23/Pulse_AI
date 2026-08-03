package org.example.pulse_ai.domain.radar;

/** Форматы размещения на площадке. */
public final class AdPinFormats {

    public static final String POST = "POST";
    public static final String PIN_1H = "PIN_1H";
    public static final String PIN_24H = "PIN_24H";
    public static final String NO_PIN = "NO_PIN";

    private AdPinFormats() {
    }

    public static String label(String code) {
        return switch (code != null ? code : "") {
            case PIN_1H -> "пост + 1 час в закрепе";
            case PIN_24H -> "пост + 24 часа в закрепе";
            case NO_PIN, POST -> "пост без закрепа";
            default -> code != null ? code : "уточнить";
        };
    }

    /** Множитель к базовой оценке цены поста. */
    public static float priceMultiplier(String code) {
        return switch (code != null ? code : POST) {
            case PIN_1H -> 1.35f;
            case PIN_24H -> 1.85f;
            case NO_PIN, POST -> 1.0f;
            default -> 1.0f;
        };
    }
}
