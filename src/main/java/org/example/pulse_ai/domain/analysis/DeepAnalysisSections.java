package org.example.pulse_ai.domain.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Разбивает LLM-разбор на секции по эмодзи-заголовкам для навигации кнопками.
 */
public final class DeepAnalysisSections {

    private static final Pattern SECTION_START = Pattern.compile(
            "^(📌|🎯|🧲|📉|💡)\\s*\\*\\*(.+?)\\*\\*|^(📌|🎯|🧲|📉|💡)\\s*(.+)$",
            Pattern.MULTILINE);

    private static final String[] SECTION_IDS = {"main", "audience", "hooks", "drops", "growth"};
    private static final String[] SECTION_EMOJI = {"📌", "🎯", "🧲", "📉", "💡"};
    private static final String[] SECTION_SHORT = {"Главное", "Аудитория", "Цепляет", "Просадки", "Идеи"};

    private DeepAnalysisSections() {
    }

    public record Section(int index, String id, String emoji, String title, String body) {
    }

    public static List<Section> parse(String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return List.of();
        }
        String text = analysis.trim();
        List<Integer> starts = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        // Ищем строки, начинающиеся с известных эмодзи секций
        String[] lines = text.split("\n", -1);
        int pos = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            for (int i = 0; i < SECTION_EMOJI.length; i++) {
                if (trimmed.startsWith(SECTION_EMOJI[i])) {
                    starts.add(pos);
                    headers.add(trimmed);
                    break;
                }
            }
            pos += line.length() + 1;
        }

        if (starts.isEmpty()) {
            return List.of(new Section(0, "main", "📌", "Разбор", text));
        }

        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String block = text.substring(start, end).trim();
            String header = headers.get(i);
            String emoji = SECTION_EMOJI[Math.min(i, SECTION_EMOJI.length - 1)];
            String id = SECTION_IDS[Math.min(i, SECTION_IDS.length - 1)];
            String title = extractTitle(header, emoji);
            String body = block;
            sections.add(new Section(i, id, emoji, title, body));
        }
        return sections;
    }

    private static String extractTitle(String header, String defaultEmoji) {
        String t = header;
        if (t.startsWith("**") && t.indexOf("**", 2) > 0) {
            int end = t.indexOf("**", 2);
            return t.substring(2, end).trim();
        }
        if (t.length() > 2 && !Character.isLetterOrDigit(t.charAt(0))) {
            t = t.substring(1).trim();
        }
        t = t.replaceAll("\\*+", "").trim();
        return t.isBlank() ? "Раздел" : t;
    }

    public static String shortLabel(int index) {
        if (index >= 0 && index < SECTION_SHORT.length) {
            return SECTION_EMOJI[index] + " " + SECTION_SHORT[index];
        }
        return "§" + (index + 1);
    }

    public static boolean isIdeasFunnelIndex(int index, int total) {
        return total > 0 && index == total - 1;
    }

    public static int ideasFunnelIndex(int total) {
        return Math.max(0, total - 1);
    }

    public static int sectionCount() {
        return SECTION_SHORT.length;
    }
}
