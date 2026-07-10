package org.example.pulse_ai.stats.external;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExternalScrapeSupport {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private ExternalScrapeSupport() {
    }

    static Document fetch(String url, int timeoutMs) throws Exception {
        return Jsoup.connect(url)
                .userAgent(UA)
                .header("Accept-Language", "ru,en;q=0.9")
                .timeout(timeoutMs)
                .followRedirects(true)
                .get();
    }

    static String normalizeUsername(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        s = s.replace("https://", "").replace("http://", "");
        s = s.replace("t.me/", "").replace("telegram.me/", "");
        s = s.replace("tgstat.ru/channel/", "").replace("telemetr.me/channels/", "");
        s = s.replace("telega.in/channels/", "").replace("telega.in/c/", "");
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        int slash = s.indexOf('/');
        if (slash > 0) {
            s = s.substring(0, slash);
        }
        int q = s.indexOf('?');
        if (q > 0) {
            s = s.substring(0, q);
        }
        return s.trim();
    }

    /** Ищет число рядом с меткой (число может стоять до или после метки). */
    static Long findNumberNearLabel(String text, String... labelKeywords) {
        String lower = text.toLowerCase();
        for (String keyword : labelKeywords) {
            String kw = keyword.toLowerCase();
            int idx = lower.indexOf(kw);
            if (idx < 0) {
                continue;
            }
            // окно ±40 символов вокруг метки
            int from = Math.max(0, idx - 40);
            int to = Math.min(text.length(), idx + kw.length() + 40);
            String window = text.substring(from, to);
            Long value = extractFirstNumber(window);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static final Pattern NUMBER = Pattern.compile("([0-9][0-9\\s.,]*)\\s*([KkМмkmКк]|тыс|млн)?");

    static Long extractFirstNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = NUMBER.matcher(raw.replace("\u00a0", " "));
        while (m.find()) {
            String digits = m.group(1).replaceAll("[\\s]", "");
            if (digits.isBlank()) {
                continue;
            }
            String suffix = m.group(2);
            try {
                double base;
                if (digits.contains(",") && !digits.contains(".")) {
                    base = Double.parseDouble(digits.replace(",", "."));
                } else {
                    base = Double.parseDouble(digits.replace(",", ""));
                }
                double multiplier = 1;
                if (suffix != null) {
                    String s = suffix.toLowerCase();
                    if (s.equals("k") || s.equals("к") || s.equals("тыс")) {
                        multiplier = 1_000;
                    } else if (s.equals("m") || s.equals("м") || s.equals("млн")) {
                        multiplier = 1_000_000;
                    }
                }
                long result = Math.round(base * multiplier);
                if (result > 0) {
                    return result;
                }
            } catch (NumberFormatException ignored) {
                // try next match
            }
        }
        return null;
    }

    static Double extractPercent(String text, String... labelKeywords) {
        String lower = text.toLowerCase();
        for (String keyword : labelKeywords) {
            String kw = keyword.toLowerCase();
            int idx = lower.indexOf(kw);
            if (idx < 0) {
                continue;
            }
            int from = Math.max(0, idx - 40);
            int to = Math.min(text.length(), idx + kw.length() + 40);
            Matcher m = Pattern.compile("([0-9]+[.,]?[0-9]*)\\s*%").matcher(text.substring(from, to));
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", "."));
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return null;
    }
}
