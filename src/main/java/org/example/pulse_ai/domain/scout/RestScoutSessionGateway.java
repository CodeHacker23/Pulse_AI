package org.example.pulse_ai.domain.scout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseScoutProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class RestScoutSessionGateway implements ScoutSessionGateway {

    private final PulseScoutProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Override
    public SendResult sendDirectMessage(long scoutAccountId, String username, String text) {
        return post("/v1/dm/send", Map.of(
                "accountId", scoutAccountId,
                "username", username,
                "text", text), node -> {
            if (node.path("ok").asBoolean(false)) {
                return SendResult.success(node.path("messageId").asLong(0));
            }
            return SendResult.failed(node.path("error").asText("send failed"));
        }, SendResult.failed("sidecar error"));
    }

    @Override
    public ParseMembersResult parseGroupMembers(long scoutAccountId, String groupLink, int limit) {
        return post("/v1/group/members", Map.of(
                "accountId", scoutAccountId,
                "link", groupLink,
                "limit", limit), node -> {
            if (!node.path("ok").asBoolean(false)) {
                return ParseMembersResult.failed(node.path("error").asText("parse failed"));
            }
            List<String> usernames = new ArrayList<>();
            for (JsonNode u : node.path("usernames")) {
                usernames.add(u.asText());
            }
            return new ParseMembersResult(true, usernames, null);
        }, ParseMembersResult.failed("sidecar error"));
    }

    @Override
    public ParseAudienceResult parseAudience(long scoutAccountId, String groupLink, int limit, int minScore) {
        return post("/v1/audience/parse", Map.of(
                "accountId", scoutAccountId,
                "link", groupLink,
                "limit", limit,
                "minScore", minScore), node -> {
            if (!node.path("ok").asBoolean(false)) {
                return ParseAudienceResult.failed(node.path("error").asText("parse failed"));
            }
            List<AudienceMember> users = new ArrayList<>();
            for (JsonNode u : node.path("users")) {
                String username = u.path("username").asText("");
                if (username.isBlank()) {
                    continue;
                }
                users.add(new AudienceMember(
                        username,
                        u.path("score").asInt(0),
                        u.path("tier").asText("")));
            }
            return new ParseAudienceResult(true, users, null);
        }, ParseAudienceResult.failed("sidecar error"));
    }

    @Override
    public ScanChatResult scanChatKeywords(long scoutAccountId, String chatLink, List<String> keywords) {
        return post("/v1/chat/scan", Map.of(
                "accountId", scoutAccountId,
                "link", chatLink,
                "keywords", keywords), node -> {
            if (!node.path("ok").asBoolean(false)) {
                return ScanChatResult.failed(node.path("error").asText("scan failed"));
            }
            List<ChatHit> hits = new ArrayList<>();
            for (JsonNode h : node.path("hits")) {
                hits.add(new ChatHit(
                        h.path("snippet").asText(""),
                        h.path("keyword").asText("")));
            }
            return new ScanChatResult(true, hits, null);
        }, ScanChatResult.failed("sidecar error"));
    }

    @Override
    public JoinResult joinChat(long scoutAccountId, String link) {
        return post("/v1/chat/join", Map.of("accountId", scoutAccountId, "link", link), node -> {
            if (node.path("ok").asBoolean(false)) {
                return new JoinResult(true, node.path("title").asText(""), null);
            }
            return JoinResult.failed(node.path("error").asText("join failed"));
        }, JoinResult.failed("sidecar error"));
    }

    @Override
    public VacuumResult vacuumPosts(long scoutAccountId, String link, int limit) {
        return post("/v1/chat/vacuum", Map.of(
                "accountId", scoutAccountId,
                "link", link,
                "limit", limit), node -> {
            if (!node.path("ok").asBoolean(false)) {
                return VacuumResult.failed(node.path("error").asText("vacuum failed"));
            }
            List<Map<String, Object>> posts = new ArrayList<>();
            for (JsonNode p : node.path("posts")) {
                Map<String, Object> row = new HashMap<>();
                p.fields().forEachRemaining(e -> row.put(e.getKey(),
                        e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText()));
                posts.add(row);
            }
            return new VacuumResult(true, posts, null);
        }, VacuumResult.failed("sidecar error"));
    }

    @Override
    public SimpleResult spamBotStart(long scoutAccountId) {
        return post("/v1/spambot/start", Map.of("accountId", scoutAccountId), node -> {
            if (node.path("ok").asBoolean(false)) {
                return SimpleResult.ok(node.path("reply").asText("ok"));
            }
            return SimpleResult.failed(node.path("error").asText("spambot failed"));
        }, SimpleResult.failed("sidecar error"));
    }

    @Override
    public SimpleResult rotateProxy(long scoutAccountId) {
        return post("/v1/proxy/rotate", Map.of("accountId", scoutAccountId), node -> {
            if (node.path("ok").asBoolean(false)) {
                return SimpleResult.ok(node.path("proxy").toString());
            }
            return SimpleResult.failed(node.path("error").asText("rotate failed"));
        }, SimpleResult.failed("sidecar error"));
    }

    @Override
    public SimpleResult assignProxy(long scoutAccountId) {
        return post("/v1/proxy/assign", Map.of("accountId", scoutAccountId), node -> {
            if (node.path("ok").asBoolean(false)) {
                return SimpleResult.ok(node.path("proxy").toString());
            }
            return SimpleResult.failed(node.path("error").asText("assign failed"));
        }, SimpleResult.failed("sidecar error"));
    }

    @Override
    public ProxyImportResult importProxies(String text) {
        try {
            String url = properties.getSidecarUrl().replaceAll("/+$", "") + "/v1/proxy/import";
            String json = objectMapper.writeValueAsString(Map.of("text", text != null ? text : ""));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getSidecarTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("Scout sidecar /v1/proxy/import -> HTTP {}", response.statusCode());
                return new ProxyImportResult(false, 0, 0, 0,
                        "sidecar HTTP " + response.statusCode()
                                + " — перезапусти scout-sidecar (нужен /v1/proxy/import)");
            }
            JsonNode node = objectMapper.readTree(response.body());
            if (!node.path("ok").asBoolean(false)) {
                return new ProxyImportResult(false, 0, 0, 0, node.path("error").asText("import failed"));
            }
            return new ProxyImportResult(true,
                    node.path("added").asInt(0),
                    node.path("total").asInt(0),
                    node.path("valid").asInt(0),
                    null);
        } catch (Exception ex) {
            log.warn("Scout sidecar /v1/proxy/import failed: {}", ex.getMessage());
            return new ProxyImportResult(false, 0, 0, 0,
                    "sidecar недоступен: " + ex.getMessage());
        }
    }

    @Override
    public ProxyListResult listProxies() {
        try {
            String url = properties.getSidecarUrl().replaceAll("/+$", "") + "/v1/proxy/list";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getSidecarTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("Scout sidecar /v1/proxy/list -> HTTP {}", response.statusCode());
                return new ProxyListResult(false, List.of(), Map.of(),
                        "sidecar HTTP " + response.statusCode()
                                + " — перезапусти scout-sidecar (нужен /v1/proxy/list)");
            }
            JsonNode node = objectMapper.readTree(response.body());
            if (!node.path("ok").asBoolean(false)) {
                return new ProxyListResult(false, List.of(), Map.of(), node.path("error").asText("list failed"));
            }
            List<Map<String, Object>> proxies = new ArrayList<>();
            for (JsonNode p : node.path("proxies")) {
                Map<String, Object> row = new HashMap<>();
                p.fields().forEachRemaining(e -> row.put(e.getKey(),
                        e.getValue().isBoolean() ? e.getValue().asBoolean()
                                : e.getValue().isNumber() ? e.getValue().numberValue()
                                : e.getValue().asText()));
                proxies.add(row);
            }
            Map<String, Object> assignments = new HashMap<>();
            node.path("assignments").fields()
                    .forEachRemaining(e -> assignments.put(e.getKey(), e.getValue().asInt()));
            return new ProxyListResult(true, proxies, assignments, null);
        } catch (Exception ex) {
            log.warn("Scout sidecar /v1/proxy/list failed: {}", ex.getMessage());
            return new ProxyListResult(false, List.of(), Map.of(),
                    "sidecar недоступен: " + ex.getMessage());
        }
    }

    @Override
    public SimpleResult purgeInvalidProxies() {
        return post("/v1/proxy/purge-invalid", Map.of(), node -> {
            if (node.path("ok").asBoolean(false)) {
                return SimpleResult.ok("removed=" + node.path("removed").asInt(0));
            }
            return SimpleResult.failed("purge failed");
        }, SimpleResult.failed("sidecar error"));
    }

    private <T> T get(String path, JsonMapper<T> mapper, T fallback) {
        try {
            String url = properties.getSidecarUrl().replaceAll("/+$", "") + path;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(properties.getSidecarTimeoutMs(), 60_000)))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return fallback;
            }
            return mapper.apply(objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            log.warn("Scout sidecar GET {} failed: {}", path, ex.getMessage());
            return fallback;
        }
    }

    private <T> T post(String path, Map<String, Object> body, JsonMapper<T> mapper, T fallback) {
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
            String raw = response.body() != null ? response.body() : "";
            if (response.statusCode() >= 400) {
                log.warn("Scout sidecar {} -> HTTP {} {}", path, response.statusCode(),
                        raw.length() > 200 ? raw.substring(0, 200) : raw);
                try {
                    JsonNode node = objectMapper.readTree(raw.isBlank() ? "{}" : raw);
                    return mapper.apply(node);
                } catch (Exception ignored) {
                    return fallback;
                }
            }
            JsonNode node = objectMapper.readTree(raw.isBlank() ? "{}" : raw);
            return mapper.apply(node);
        } catch (Exception ex) {
            log.warn("Scout sidecar {} failed: {}", path, ex.getMessage());
            return fallback;
        }
    }

    @FunctionalInterface
    private interface JsonMapper<T> {
        T apply(JsonNode node);
    }
}
