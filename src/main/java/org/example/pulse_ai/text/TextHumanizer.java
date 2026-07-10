package org.example.pulse_ai.text;

/**
 * Убирает типичные «ИИ-штампы» в пунктуации и кавычках.
 */
public final class TextHumanizer {

    private TextHumanizer() {
    }

    public static String humanize(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String s = text;
        s = s.replace("\u00AB", "").replace("\u00BB", "");
        s = s.replace("\u201E", "\"").replace("\u201C", "\"").replace("\u201D", "\"");
        s = s.replace("\u2039", "").replace("\u203A", "");
        s = s.replace('\u2014', '-').replace('\u2013', '-');
        s = s.replace("\u2026", "...");
        s = s.replaceAll("\\s{2,}", " ");
        return s.trim();
    }
}
