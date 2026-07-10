package org.example.pulse_ai.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for Telegram HTML parse mode.
 * Telegram HTML only supports a small tag whitelist and requires &amp;, &lt;, &gt; to be escaped
 * in all dynamic content, otherwise the whole message fails to send.
 */
public final class TgHtml {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*", Pattern.DOTALL);
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w_])_([^_\\n]+)_(?![\\w_])");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("(?m)^\\s{0,3}[-*]\\s+");
    /** @-упоминания: делаем курсивом и НЕкликабельными (word joiner после @ отключает автоссылку). */
    private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z][A-Za-z0-9_]{1,})");
    private static final String WORD_JOINER = "\u2060";

    private TgHtml() {
    }

    /** Escapes text so it is safe to place inside a Telegram HTML message. */
    public static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** Wraps escaped text in a bold tag. */
    public static String b(String text) {
        return "<b>" + esc(text) + "</b>";
    }

    /** Wraps escaped text in an italic tag. */
    public static String i(String text) {
        return "<i>" + esc(text) + "</i>";
    }

    /**
     * Converts LLM/markdown-ish free text to safe Telegram HTML:
     * escapes everything, then re-enables **bold**, markdown headings and turns "- "/"* " into bullets.
     */
    public static String fromMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = TextHumanizer.humanize(text);
        String escaped = esc(normalized);
        escaped = HEADING.matcher(escaped).replaceAll(m -> "<b>" + Matcher.quoteReplacement(m.group(1)) + "</b>");
        escaped = BOLD.matcher(escaped).replaceAll(m -> "<b>" + Matcher.quoteReplacement(m.group(1)) + "</b>");
        escaped = ITALIC.matcher(escaped).replaceAll(m -> "<i>" + Matcher.quoteReplacement(m.group(1)) + "</i>");
        escaped = BULLET.matcher(escaped).replaceAll("• ");
        escaped = MENTION.matcher(escaped)
                .replaceAll(m -> "<i>@" + WORD_JOINER + Matcher.quoteReplacement(m.group(1)) + "</i>");
        return escaped;
    }
}
