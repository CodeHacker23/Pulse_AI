package org.example.pulse_ai.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.scout.ScoutSessionGateway;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drop proxies into {@code data/proxies-inbox.txt} (notepad) — бот подхватывает сам.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyInboxService {

    private static final Path INBOX = Paths.get("data", "proxies-inbox.txt");
    private static final Path PROCESSED_DIR = Paths.get("data", "proxies-processed");

    private final ScoutSessionGateway scoutGateway;
    private final AtomicLong lastSize = new AtomicLong(-1);
    private final AtomicLong lastModified = new AtomicLong(-1);

    public Path inboxPath() {
        return INBOX.toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void pollInbox() {
        try {
            ensureInboxExists();
            if (!Files.exists(INBOX)) {
                return;
            }
            long size = Files.size(INBOX);
            long modified = Files.getLastModifiedTime(INBOX).toMillis();
            if (size == 0) {
                lastSize.set(0);
                lastModified.set(modified);
                return;
            }
            if (size == lastSize.get() && modified == lastModified.get()) {
                return;
            }
            // debounce: wait until file stable (not being typed)
            Thread.sleep(800);
            long size2 = Files.size(INBOX);
            long mod2 = Files.getLastModifiedTime(INBOX).toMillis();
            if (size2 != size || mod2 != modified) {
                return;
            }
            Map<String, Object> result = pullNow();
            if (Boolean.TRUE.equals(result.get("ok")) && ((Number) result.getOrDefault("added", 0)).intValue() >= 0) {
                log.info("Proxy inbox imported: {}", result);
            }
        } catch (Exception ex) {
            log.debug("Proxy inbox poll: {}", ex.getMessage());
        }
    }

    public Map<String, Object> pullNow() {
        Map<String, Object> out = new HashMap<>();
        try {
            ensureInboxExists();
            if (!Files.exists(INBOX) || Files.size(INBOX) == 0) {
                out.put("ok", true);
                out.put("added", 0);
                out.put("message", "inbox empty");
                out.put("path", inboxPath().toString());
                return out;
            }
            String raw = Files.readString(INBOX, StandardCharsets.UTF_8);
            String text = stripComments(raw);
            if (text.isBlank()) {
                out.put("ok", true);
                out.put("added", 0);
                out.put("message", "only comments / empty");
                out.put("path", inboxPath().toString());
                lastSize.set(Files.size(INBOX));
                lastModified.set(Files.getLastModifiedTime(INBOX).toMillis());
                return out;
            }
            var result = scoutGateway.importProxies(text);
            out.put("ok", result.ok());
            out.put("added", result.added());
            out.put("total", result.total());
            out.put("valid", result.valid());
            out.put("error", result.error());
            out.put("path", inboxPath().toString());

            Files.createDirectories(PROCESSED_DIR);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path archive = PROCESSED_DIR.resolve("proxies-" + stamp + ".txt");
            Files.move(INBOX, archive, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(INBOX, "", StandardCharsets.UTF_8);
            lastSize.set(0);
            lastModified.set(Files.getLastModifiedTime(INBOX).toMillis());
            out.put("archived", archive.toAbsolutePath().toString());
            return out;
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("error", ex.getMessage());
            out.put("path", inboxPath().toString());
            return out;
        }
    }

    static String stripComments(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }

    private void ensureInboxExists() throws Exception {
        Files.createDirectories(INBOX.getParent());
        if (!Files.exists(INBOX)) {
            Files.writeString(INBOX, """
                    # Pulse AI — вставь прокси сюда (по одному на строку) и сохрани файл.
                    # Бот подхватит через ~15 сек и очистит inbox.
                    # Форматы:
                    # host:port
                    # host:port:user:pass
                    # socks5://user:pass@host:port
                    
                    """, StandardCharsets.UTF_8);
            lastSize.set(Files.size(INBOX));
            lastModified.set(Files.getLastModifiedTime(INBOX).toMillis());
        }
    }
}
