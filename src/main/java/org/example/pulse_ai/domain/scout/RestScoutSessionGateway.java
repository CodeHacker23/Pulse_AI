package org.example.pulse_ai.domain.scout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseScoutProperties;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class RestScoutSessionGateway implements ScoutSessionGateway {

    private final PulseScoutProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
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

    private <T> T post(String path, Map<String, Object> body, JsonMapper<T> mapper, T fallback) {
        try {
            String url = properties.getSidecarUrl().replaceAll("/+$", "") + path;
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getSidecarTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("Scout sidecar {} -> HTTP {}", path, response.statusCode());
                return fallback;
            }
            JsonNode node = objectMapper.readTree(response.body());
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
