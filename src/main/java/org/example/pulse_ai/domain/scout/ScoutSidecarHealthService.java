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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoutSidecarHealthService {

    private final PulseScoutProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public HealthStatus ping() {
        if (!properties.sidecarConfigured()) {
            return HealthStatus.unconfigured();
        }
        try {
            String url = properties.getSidecarUrl().replaceAll("/+$", "") + "/health";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return HealthStatus.down("HTTP " + response.statusCode());
            }
            JsonNode node = objectMapper.readTree(response.body());
            if (!node.path("ok").asBoolean(false)) {
                return HealthStatus.down(node.path("error").asText("sidecar unhealthy"));
            }
            List<ScoutAccountHealthInfo> accounts = new ArrayList<>();
            for (JsonNode acc : node.path("accounts")) {
                accounts.add(new ScoutAccountHealthInfo(
                        acc.path("id").asLong(0),
                        acc.path("label").asText(""),
                        acc.path("type").asText("")));
            }
            return HealthStatus.up(accounts);
        } catch (Exception ex) {
            log.debug("Scout sidecar health check failed: {}", ex.getMessage());
            return HealthStatus.down(ex.getMessage());
        }
    }

    public record HealthStatus(
            boolean configured,
            boolean reachable,
            String detail,
            List<ScoutAccountHealthInfo> accounts
    ) {
        public static HealthStatus unconfigured() {
            return new HealthStatus(false, false, "URL не задан", List.of());
        }

        public static HealthStatus down(String detail) {
            return new HealthStatus(true, false, detail, List.of());
        }

        public static HealthStatus up(List<ScoutAccountHealthInfo> accounts) {
            return new HealthStatus(true, true, null, accounts);
        }
    }
}
