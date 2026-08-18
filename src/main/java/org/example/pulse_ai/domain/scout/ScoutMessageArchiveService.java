package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.ScoutMessageArchiveEntity;
import org.example.pulse_ai.persistence.repository.ScoutMessageArchiveRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Ops-бэкап ЛС скаутов. Переживает сгорание сессии; не релей и не подмена live-кабинета.
 * Медиа кладётся в {@code data/scout_media/} сразу при получении (TTL у TG короткий).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoutMessageArchiveService {

    private static final Path MEDIA_ROOT = Path.of("data", "scout_media");
    private static final long MAX_MEDIA_BYTES = 25L * 1024 * 1024;

    private final ScoutMessageArchiveRepository repository;

    @Transactional
    public void ingest(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        Object batch = body.get("events");
        if (batch instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    ingestOne(cast(map), null, null, null, null);
                }
            }
            return;
        }
        ingestOne(body, null, null, null, null);
    }

    @Transactional
    public void ingestWithMedia(
            Map<String, Object> meta,
            byte[] fileBytes,
            String originalFilename,
            String contentType
    ) {
        if (fileBytes != null && fileBytes.length > MAX_MEDIA_BYTES) {
            log.warn("archive media too large: {} bytes", fileBytes.length);
            fileBytes = null;
        }
        String kind = str(meta.get("mediaKind"));
        if (kind.isBlank()) {
            kind = guessKind(contentType, originalFilename);
        }
        String savedPath = null;
        if (fileBytes != null && fileBytes.length > 0) {
            Long accountId = asLong(meta.get("accountId"));
            Long messageId = asLong(meta.get("messageId"));
            String peerId = normalizePeer(str(meta.get("peerId")));
            if (accountId != null && messageId != null && !peerId.isBlank()) {
                savedPath = storeMedia(accountId, peerId, messageId, fileBytes, originalFilename, kind, contentType);
                if (savedPath != null) {
                    meta.put("mediaSize", fileBytes.length);
                }
            }
        }
        ingestOne(meta, kind, savedPath, contentType, originalFilename);
    }

    @Transactional
    public void ingestLiveMessages(long accountId, String peer, List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty() || peer == null || peer.isBlank()) {
            return;
        }
        String peerId = normalizePeer(peer);
        for (Map<String, Object> m : messages) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("accountId", accountId);
            ev.put("peerId", peerId);
            if (!peerId.equals(peer.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT))
                    && !peer.chars().allMatch(Character::isDigit)) {
                ev.put("peerUsername", peer.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT));
            }
            ev.put("messageId", m.get("id"));
            ev.put("out", Boolean.TRUE.equals(m.get("out")) || Boolean.TRUE.equals(m.get("from_me")));
            ev.put("text", m.get("text"));
            ev.put("date", m.get("date"));
            ev.put("event", "new");
            ingestOne(ev, null, null, null, null);
        }
    }

    @Transactional
    public void ingestOutgoing(long accountId, String peer, String text, Object messageId) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("accountId", accountId);
        ev.put("peerId", normalizePeer(peer));
        if (peer != null && !peer.chars().allMatch(Character::isDigit)) {
            ev.put("peerUsername", peer.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT));
        }
        ev.put("messageId", messageId);
        ev.put("out", true);
        ev.put("text", text);
        ev.put("date", Instant.now().toString());
        ev.put("event", "new");
        ingestOne(ev, null, null, null, null);
    }

    public Map<String, Object> dialogsFromArchive(long accountId, int limit, String liveError) {
        List<ScoutMessageArchiveEntity> recent = repository.findTop400ByScoutAccountIdOrderByMessageAtDesc(accountId);
        Map<String, Map<String, Object>> byPeer = new LinkedHashMap<>();
        for (ScoutMessageArchiveEntity row : recent) {
            String key = row.getPeerId();
            if (byPeer.containsKey(key)) {
                continue;
            }
            String preview = previewText(row);
            Map<String, Object> dlg = new LinkedHashMap<>();
            dlg.put("peer_id", row.getPeerId());
            dlg.put("username", row.getPeerUsername() != null ? row.getPeerUsername() : "");
            dlg.put("name", row.getPeerName() != null && !row.getPeerName().isBlank()
                    ? row.getPeerName()
                    : (row.getPeerUsername() != null ? row.getPeerUsername() : row.getPeerId()));
            dlg.put("kind", "user");
            dlg.put("unread", 0);
            dlg.put("last_message", preview);
            dlg.put("date", row.getMessageAt() != null ? row.getMessageAt().toString() : null);
            dlg.put("from_archive", true);
            byPeer.put(key, dlg);
            if (byPeer.size() >= Math.max(1, Math.min(limit, 80))) {
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source", "archive");
        out.put("liveError", liveError);
        out.put("dialogs", new ArrayList<>(byPeer.values()));
        return out;
    }

    public Map<String, Object> messagesFromArchive(long accountId, String peer, int limit, String liveError) {
        String key = normalizePeer(peer);
        List<ScoutMessageArchiveEntity> rows = repository
                .findTop80ByScoutAccountIdAndPeerIdOrderByMessageAtAscTgMessageIdAsc(accountId, key);
        if (rows.isEmpty() && !key.chars().allMatch(Character::isDigit)) {
            rows = repository.findTop80ByScoutAccountIdAndPeerUsernameIgnoreCaseOrderByMessageAtAscTgMessageIdAsc(
                    accountId, key);
        }
        int cap = Math.max(1, Math.min(limit, 80));
        if (rows.size() > cap) {
            rows = rows.subList(rows.size() - cap, rows.size());
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ScoutMessageArchiveEntity row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getTgMessageId());
            m.put("out", "OUT".equals(row.getDirection()));
            m.put("from_me", "OUT".equals(row.getDirection()));
            m.put("text", row.isDeleted()
                    ? (row.getBody() != null && !row.getBody().isBlank() ? row.getBody() : "удалено")
                    : (row.getBody() != null ? row.getBody() : ""));
            m.put("date", row.getMessageAt() != null ? row.getMessageAt().toString() : null);
            m.put("edited", row.isEdited());
            m.put("deleted", row.isDeleted());
            if (row.getMediaKind() != null && !row.getMediaKind().isBlank()) {
                m.put("mediaKind", row.getMediaKind());
                m.put("mediaMime", row.getMediaMime());
                m.put("mediaFileName", row.getMediaFileName());
                m.put("mediaSize", row.getMediaSize());
                if (row.getMediaPath() != null && !row.getMediaPath().isBlank()) {
                    m.put("hasMedia", true);
                    m.put("mediaUrl", "/admin/api/archive/media?accountId=" + accountId
                            + "&peer=" + row.getPeerId()
                            + "&messageId=" + row.getTgMessageId());
                }
            }
            messages.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source", "archive");
        out.put("liveError", liveError);
        out.put("messages", messages);
        return out;
    }

    public Optional<MediaFile> openMedia(long accountId, String peer, long messageId) {
        String peerId = normalizePeer(peer);
        return repository.findByScoutAccountIdAndPeerIdAndTgMessageId(accountId, peerId, messageId)
                .filter(row -> row.getMediaPath() != null && !row.getMediaPath().isBlank())
                .flatMap(row -> {
                    Path file = resolveSafe(row.getMediaPath());
                    if (file == null || !Files.isRegularFile(file)) {
                        return Optional.empty();
                    }
                    String mime = row.getMediaMime() != null ? row.getMediaMime() : "application/octet-stream";
                    String name = row.getMediaFileName() != null ? row.getMediaFileName() : file.getFileName().toString();
                    return Optional.of(new MediaFile(new FileSystemResource(file), mime, name));
                });
    }

    public record MediaFile(Resource resource, String mime, String filename) {
    }

    private void ingestOne(
            Map<String, Object> body,
            String mediaKind,
            String mediaPath,
            String mediaMime,
            String mediaFileName
    ) {
        Long accountId = asLong(body.get("accountId"));
        Long messageId = asLong(body.get("messageId"));
        String peerId = str(body.get("peerId"));
        if (accountId == null || messageId == null || peerId.isBlank()) {
            return;
        }
        peerId = normalizePeer(peerId);
        String event = str(body.get("event")).toLowerCase(Locale.ROOT);
        ScoutMessageArchiveEntity row = repository
                .findByScoutAccountIdAndPeerIdAndTgMessageId(accountId, peerId, messageId)
                .orElseGet(ScoutMessageArchiveEntity::new);
        boolean created = row.getId() == null;
        row.setScoutAccountId(accountId);
        row.setPeerId(peerId);
        String username = str(body.get("peerUsername"));
        if (!username.isBlank()) {
            row.setPeerUsername(username.toLowerCase(Locale.ROOT).replaceFirst("^@", ""));
        }
        String name = str(body.get("peerName"));
        if (!name.isBlank()) {
            row.setPeerName(clip(name, 256));
        }
        row.setTgMessageId(messageId);
        if (body.containsKey("out")) {
            row.setDirection(truthy(body.get("out")) ? "OUT" : "IN");
        } else if (created) {
            row.setDirection("IN");
        }
        if ("delete".equals(event)) {
            row.setDeleted(true);
        } else {
            String text = str(body.get("text"));
            if (!text.isEmpty() || created) {
                row.setBody(clip(text, 8000));
            }
            if ("edit".equals(event) && !created) {
                row.setEdited(true);
            }
        }
        Instant at = parseInstant(body.get("date"));
        if (at != null && (created || row.getMessageAt() == null)) {
            row.setMessageAt(at);
        }
        String kind = mediaKind != null && !mediaKind.isBlank() ? mediaKind : str(body.get("mediaKind"));
        if (!kind.isBlank()) {
            row.setMediaKind(clip(kind.toLowerCase(Locale.ROOT), 16));
        }
        if (mediaPath != null && !mediaPath.isBlank()) {
            row.setMediaPath(mediaPath);
        }
        String mime = mediaMime != null ? mediaMime : str(body.get("mediaMime"));
        if (!mime.isBlank()) {
            row.setMediaMime(clip(mime, 128));
        }
        String fileName = mediaFileName != null ? mediaFileName : str(body.get("mediaFileName"));
        if (!fileName.isBlank()) {
            row.setMediaFileName(clip(fileName, 256));
        }
        Long size = asLong(body.get("mediaSize"));
        if (size != null) {
            row.setMediaSize(size);
        }
        repository.save(row);
    }

    private String storeMedia(
            long accountId,
            String peerId,
            long messageId,
            byte[] bytes,
            String originalFilename,
            String kind,
            String contentType
    ) {
        try {
            String ext = extensionOf(originalFilename, kind, contentType);
            Path dir = MEDIA_ROOT.resolve(String.valueOf(accountId)).resolve(safeSegment(peerId));
            Files.createDirectories(dir);
            Path file = dir.resolve(messageId + ext);
            Files.write(file, bytes);
            return accountId + "/" + safeSegment(peerId) + "/" + messageId + ext;
        } catch (IOException ex) {
            log.warn("archive media store failed: {}", ex.getMessage());
            return null;
        }
    }

    private static Path resolveSafe(String relative) {
        if (relative == null || relative.isBlank() || relative.contains("..")) {
            return null;
        }
        Path root = MEDIA_ROOT.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            return null;
        }
        return file;
    }

    private static String previewText(ScoutMessageArchiveEntity row) {
        if (row.isDeleted()) {
            return "удалено";
        }
        if (row.getBody() != null && !row.getBody().isBlank()) {
            return clip(row.getBody(), 120);
        }
        return switch (row.getMediaKind() != null ? row.getMediaKind() : "") {
            case "photo" -> "🖼 фото";
            case "video" -> "🎬 видео";
            case "voice" -> "🎤 голосовое";
            case "audio" -> "🎵 аудио";
            case "sticker" -> "стикер";
            case "document" -> "📎 файл";
            default -> row.getMediaPath() != null ? "📎 вложение" : "";
        };
    }

    private static String guessKind(String mime, String filename) {
        String m = mime != null ? mime.toLowerCase(Locale.ROOT) : "";
        String f = filename != null ? filename.toLowerCase(Locale.ROOT) : "";
        if (m.startsWith("image/") || f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".png") || f.endsWith(".webp")) {
            return "photo";
        }
        if (m.startsWith("video/") || f.endsWith(".mp4") || f.endsWith(".mov")) {
            return "video";
        }
        if (m.contains("ogg") || f.endsWith(".ogg") || f.endsWith(".oga")) {
            return "voice";
        }
        if (m.startsWith("audio/")) {
            return "audio";
        }
        return "document";
    }

    private static String extensionOf(String filename, String kind, String mime) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.[a-z0-9]{1,8}")) {
                    return ext;
                }
            }
        }
        if (mime != null) {
            return switch (mime.toLowerCase(Locale.ROOT)) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif" -> ".gif";
                case "video/mp4" -> ".mp4";
                case "audio/ogg", "application/ogg" -> ".ogg";
                default -> "";
            };
        }
        return switch (kind != null ? kind : "") {
            case "photo" -> ".jpg";
            case "video" -> ".mp4";
            case "voice" -> ".ogg";
            case "audio" -> ".mp3";
            default -> ".bin";
        };
    }

    private static String safeSegment(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Map<String, Object> cast(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null) {
                out.put(String.valueOf(k), v);
            }
        });
        return out;
    }

    private static String normalizePeer(String peer) {
        if (peer == null) {
            return "";
        }
        String s = peer.strip();
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        return s;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equals(s)) {
                return null;
            }
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }

    private static Instant parseInstant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ex) {
            try {
                return Instant.parse(s.replace(' ', 'T'));
            } catch (DateTimeParseException ex2) {
                return null;
            }
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
