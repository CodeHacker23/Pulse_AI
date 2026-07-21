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
        // Нормализуем переносы строк, НЕ схлопывая абзацы (иначе пост превращается в кашу).
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("[ \t\u00A0]{2,}", " "); // лишние пробелы/табы — но не переносы строк
        s = s.replaceAll(" *\n *", "\n");          // убираем пробелы по краям строк
        s = s.replaceAll("\n{3,}", "\n\n");        // максимум одна пустая строка между абзацами
        return s.trim();
    }
}
