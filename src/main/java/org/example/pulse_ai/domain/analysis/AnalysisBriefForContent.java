package org.example.pulse_ai.domain.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сжимает разделы глубокого разбора в короткий бриф для идей и черновиков,
 * чтобы контент опирался на выводы бота, а не игнорировал их.
 */
public final class AnalysisBriefForContent {

    private static final int MAX_SECTION = 900;
    private static final int MAX_TOTAL = 2800;

    private AnalysisBriefForContent() {
    }

    public static String fromSections(List<DeepAnalysisSections.Section> sections) {
        if (sections == null || sections.isEmpty()) {
            return "";
        }
        Map<String, String> byId = new HashMap<>();
        for (DeepAnalysisSections.Section s : sections) {
            if (s == null || s.body() == null || s.body().isBlank()) {
                continue;
            }
            String id = s.id() != null ? s.id() : "main";
            byId.putIfAbsent(id, s.body());
        }
        StringBuilder sb = new StringBuilder();
        append(sb, "Главное", byId.get("main"));
        append(sb, "О чём канал и аудитория", byId.get("audience"));
        append(sb, "Почему цепляет", byId.get("hooks"));
        append(sb, "Где теряются просмотры", byId.get("drops"));
        append(sb, "Что усилить / шаги роста", byId.get("growth"));
        if (sb.isEmpty()) {
            // неизвестная разметка — склеим тела по порядку
            for (DeepAnalysisSections.Section s : sections) {
                append(sb, s.title() != null ? s.title() : "Раздел", s.body());
            }
        }
        if (sb.isEmpty()) {
            return "";
        }
        String out = sb.toString().trim();
        return out.length() > MAX_TOTAL ? out.substring(0, MAX_TOTAL) + "…" : out;
    }

    public static String promptBlock(String brief) {
        if (brief == null || brief.isBlank()) {
            return "";
        }
        return """
                ОБЯЗАТЕЛЬНО учти выводы последнего разбора этого канала (не игнорируй):
                %s

                Правила по брифу:
                - идеи и тексты должны закрывать «где теряются просмотры» и «что усилить»;
                - первая строка — сильный крючок (не скучное описание);
                - чередуй углы и форматы, не повторяй одни и те же темы разбора;
                - пиши под аудиторию из раздела «о чём канал».

                """.formatted(brief.trim());
    }

    private static void append(StringBuilder sb, String label, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        String t = body.trim();
        if (t.length() > MAX_SECTION) {
            t = t.substring(0, MAX_SECTION) + "…";
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(label).append(":\n").append(t);
    }
}
