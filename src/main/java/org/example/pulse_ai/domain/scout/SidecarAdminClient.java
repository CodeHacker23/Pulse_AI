package org.example.pulse_ai.domain.scout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseScoutProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Admin-only sidecar calls: dialogs, profile, proxy check, audience parse. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SidecarAdminClient {

    private final PulseScoutProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public Map<String, Object> checkProxies() {
        return postMap("/v1/proxy/check", Map.of());
    }

    public Map<String, Object> getMe(long accountId) {
        return getMap("/v1/account/" + accountId + "/me");
    }

    public Map<String, Object> registerAccount(long id, String label, String type) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id);
        String safeLabel = (label == null || label.isBlank()) ? ("acc-" + id) : label.trim();
        String safeType = (type == null || type.isBlank()) ? "SENDER" : type.trim().toUpperCase();
        body.put("label", safeLabel);
        body.put("type", safeType);
        body.put("session", "acc-" + id);
        return postMap("/v1/accounts/register", body);
    }

    public Map<String, Object> accountStatus(long accountId) {
        return getMap("/v1/accounts/" + accountId + "/status");
    }

    public Map<String, Object> importAuthKey(long accountId, String authKeyHex, int dcId) {
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", accountId);
        body.put("authKeyHex", authKeyHex);
        body.put("dcId", dcId);
        return postMap("/v1/accounts/auth-key", body);
    }

    public Map<String, Object> restoreFromSecrets(long accountId) {
        return postMap("/v1/accounts/" + accountId + "/restore-secrets", Map.of());
    }

    public Map<String, Object> saveIdentity(long accountId, Map<String, Object> fields) {
        return postMap("/v1/accounts/" + accountId + "/identity", fields);
    }

    public Map<String, Object> uploadSession(long accountId, byte[] bytes, String filename) {
        try {
            String boundary = "----Pulse" + System.currentTimeMillis();
            String url = properties.getSidecarUrl().replaceAll("/+$", "")
                    + "/v1/accounts/" + accountId + "/session";
            String safeName = filename != null && !filename.isBlank() ? filename : "account.session";
            byte[] head = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + safeName + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] body = new byte[head.length + bytes.length + tail.length];
            System.arraycopy(head, 0, body, 0, head.length);
            System.arraycopy(bytes, 0, body, head.length, bytes.length);
            System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(properties.getSidecarTimeoutMs(), 90_000)))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return Map.of("ok", false, "error", "HTTP " + response.statusCode());
            }
            return jsonToMap(objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            return Map.of("ok", false, "error", ex.getMessage() != null ? ex.getMessage() : "error");
        }
    }

    public Map<String, Object> uploadTdata(long accountId, byte[] bytes, String filename) {
        try {
            String boundary = "----Pulse" + System.currentTimeMillis();
            String url = properties.getSidecarUrl().replaceAll("/+$", "")
                    + "/v1/accounts/" + accountId + "/tdata";
            String safeName = filename != null && !filename.isBlank() ? filename : "tdata.zip";
            byte[] head = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + safeName + "\"\r\n"
                    + "Content-Type: application/zip\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] body = new byte[head.length + bytes.length + tail.length];
            System.arraycopy(head, 0, body, 0, head.length);
            System.arraycopy(bytes, 0, body, head.length, bytes.length);
            System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(properties.getSidecarTimeoutMs(), 120_000)))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return Map.of("ok", false, "error", "HTTP " + response.statusCode());
            }
            return jsonToMap(objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            return Map.of("ok", false, "error", ex.getMessage() != null ? ex.getMessage() : "error");
        }
    }

    public Map<String, Object> updateProfile(long accountId, Map<String, Object> fields) {
        Map<String, Object> body = new HashMap<>(fields);
        body.put("accountId", accountId);
        return postMap("/v1/account/profile", body);
    }

    public Map<String, Object> updatePhoto(long accountId, byte[] bytes, String filename) {
        try {
            String boundary = "----Pulse" + System.currentTimeMillis();
            String url = properties.getSidecarUrl().replaceAll("/+$", "")
                    + "/v1/account/" + accountId + "/photo";
            byte[] head = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + (filename != null ? filename : "photo.jpg") + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] body = new byte[head.length + bytes.length + tail.length];
            System.arraycopy(head, 0, body, 0, head.length);
            System.arraycopy(bytes, 0, body, head.length, bytes.length);
            System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(properties.getSidecarTimeoutMs(), 90_000)))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return Map.of("ok", false, "error", "HTTP " + response.statusCode());
            }
            return jsonToMap(objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            return Map.of("ok", false, "error", ex.getMessage() != null ? ex.getMessage() : "error");
        }
    }

    public Map<String, Object> listDialogs(long accountId, int limit) {
        String q = "?accountId=" + accountId + "&limit=" + limit;
        return getMap("/v1/dialogs" + q);
    }

    public Map<String, Object> resolvePeer(long accountId, String query) {
        return postMap("/v1/dialogs/resolve", Map.of(
                "accountId", accountId,
                "query", query));
    }

    public Map<String, Object> dialogMessages(long accountId, String peer, int limit) {
        return postMap("/v1/dialogs/messages", Map.of(
                "accountId", accountId,
                "peer", peer,
                "limit", limit));
    }

    public Map<String, Object> markRead(long accountId, String peer) {
        return postMap("/v1/dialogs/read", Map.of(
                "accountId", accountId,
                "peer", peer));
    }

    public Map<String, Object> reply(long accountId, String peer, String text) {
        return postMap("/v1/dialogs/reply", Map.of(
                "accountId", accountId,
                "peer", peer,
                "text", text));
    }

    public Map<String, Object> parseAudience(long accountId, String link, int limit, int minScore) {
        return postMap("/v1/audience/parse", Map.of(
                "accountId", accountId,
                "link", link,
                "limit", limit,
                "minScore", minScore));
    }

    /** Дубли auth_key между .session — из-за них Telegram убивает ключи. */
    public Map<String, Object> sessionsAudit() {
        return getMap("/v1/sessions/audit");
    }

    public Map<String, Object> deleteOrphanSession(String fileName) {
        return deleteMap("/v1/sessions/orphan/" + java.net.URLEncoder.encode(
                fileName, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Убрать карточку из sidecar (accounts.json) и снести .session. */
    public Map<String, Object> deleteAccount(long accountId, boolean wipeSession) {
        return deleteMap("/v1/accounts/" + accountId + "?wipeSession=" + wipeSession);
    }

    /** Снести только сессию — карточка и данные покупки остаются. */
    public Map<String, Object> wipeSession(long accountId) {
        return postMap("/v1/accounts/" + accountId + "/wipe-session", Map.of());
    }

    private Map<String, Object> deleteMap(String path) {
        try {
            String url = properties.getSidecarUrl().replaceAll("/+$", "") + path;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(properties.getSidecarTimeoutMs(), 60_000)))
                    .DELETE()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
            return parseSidecarBody(response.statusCode(), response.body());
        } catch (Exception ex) {
            log.warn("Sidecar DELETE {} failed: {}", path, ex.getMessage());
            return Map.of("ok", false, "error", ex.getMessage() != null ? ex.getMessage() : "error");
        }
    }

    private Map<String, Object> getMap(String path) {
        try {
            String url = properties.getSidecarUrl().replaceAll("/+$", "") + path;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(properties.getSidecarTimeoutMs(), 60_000)))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
            return parseSidecarBody(response.statusCode(), response.body());
        } catch (Exception ex) {
            log.warn("Sidecar GET {} failed: {}", path, ex.getMessage());
            return Map.of("ok", false, "error", ex.getMessage() != null ? ex.getMessage() : "error");
        }
    }

    private Map<String, Object> postMap(String path, Map<String, Object> body) {
        try {
            String url = properties.getSidecarUrl().replaceAll("/+$", "") + path;
            byte[] bytes = objectMapper.writeValueAsBytes(body != null ? body : Map.of());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(properties.getSidecarTimeoutMs(), 90_000)))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
            return parseSidecarBody(response.statusCode(), response.body());
        } catch (Exception ex) {
            log.warn("Sidecar POST {} failed: {}", path, ex.getMessage());
            return Map.of("ok", false, "error", ex.getMessage() != null ? ex.getMessage() : "error");
        }
    }

    private Map<String, Object> parseSidecarBody(int status, String body) {
        Map<String, Object> out = new HashMap<>();
        if (body != null && !body.isBlank()) {
            try {
                out.putAll(jsonToMap(objectMapper.readTree(body)));
            } catch (Exception ignored) {
                out.put("raw", body.length() > 400 ? body.substring(0, 400) : body);
            }
        }
        if (status >= 400) {
            out.put("ok", false);
            if (!out.containsKey("error") || out.get("error") == null || String.valueOf(out.get("error")).isBlank()) {
                Object detail = out.get("detail");
                if (detail != null) {
                    out.put("error", "HTTP " + status + ": " + String.valueOf(detail));
                } else {
                    out.put("error", "HTTP " + status + (out.containsKey("raw") ? (" " + out.get("raw")) : ""));
                }
            }
        } else if (!out.containsKey("ok")) {
            out.put("ok", true);
        }
        return out;
    }

    private Map<String, Object> jsonToMap(JsonNode node) {
        Map<String, Object> out = new HashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), toJava(e.getValue())));
        return out;
    }

    private Object toJava(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isInt()) {
            return n.asInt();
        }
        if (n.isLong()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.numberValue();
        }
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            n.forEach(child -> list.add(toJava(child)));
            return list;
        }
        if (n.isObject()) {
            return jsonToMap(n);
        }
        return n.asText();
    }
}
