package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсинг ссылок + постановка в общий пул (медленный join делает ScoutChatJoinScheduler).
 * Раньше join шёл сразу в цикле sleep(800) — это и жгло наблюдателей.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoutChatImportService {

    private static final Pattern LINK = Pattern.compile(
            "(?:https?://)?(?:t\\.me|telegram\\.me)/(?:\\+|joinchat/)?([A-Za-z0-9_/-]+)|@([A-Za-z0-9_]{4,})",
            Pattern.CASE_INSENSITIVE);

    private final ScoutChatPoolService chatPoolService;

    public ImportResult importLinks(String rawText, Long accountIdOrNull) {
        ScoutChatPoolService.EnqueueResult r = chatPoolService.enqueueLinks(rawText, accountIdOrNull);
        if (!r.ok()) {
            return ImportResult.empty(r.error() != null ? r.error() : "ошибка пула");
        }
        List<JoinLine> lines = new ArrayList<>();
        for (String line : r.lines()) {
            lines.add(new JoinLine(line, true, line, null));
        }
        return new ImportResult(
                accountIdOrNull,
                null,
                r.totalLinks(),
                r.addedChats() + r.alreadyInPool(),
                0,
                lines,
                null,
                r.detail()
        );
    }

    static List<String> parseLinks(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            Matcher m = LINK.matcher(t);
            if (m.find()) {
                if (m.group(2) != null) {
                    out.add("@" + m.group(2));
                } else if (m.group(1) != null) {
                    String path = m.group(1);
                    if (path.toLowerCase(Locale.ROOT).startsWith("joinchat/") || path.startsWith("+")) {
                        out.add("https://t.me/" + path);
                    } else {
                        out.add("@" + path.replace("/", ""));
                    }
                }
            } else if (t.startsWith("@") || t.contains("t.me/")) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    public record JoinLine(String link, boolean ok, String title, String error) {
    }

    public record ImportResult(
            Long accountId,
            String accountLabel,
            int total,
            int ok,
            int fail,
            List<JoinLine> lines,
            String error,
            String detail
    ) {
        public static ImportResult empty(String error) {
            return new ImportResult(null, null, 0, 0, 0, List.of(), error, null);
        }
    }
}
