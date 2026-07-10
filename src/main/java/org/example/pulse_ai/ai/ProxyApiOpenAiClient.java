package org.example.pulse_ai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseAiProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class ProxyApiOpenAiClient implements LlmClient {

    private final PulseAiProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ProxyApiOpenAiClient(
            PulseAiProperties properties,
            RestTemplate proxyApiRestTemplate,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.restTemplate = proxyApiRestTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Задайте pulse.ai.api-key или переменную окружения PROXYAPI_API_KEY"
            );
        }

        String model = request.model() != null && !request.model().isBlank()
                ? request.model()
                : properties.getModel();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());

        ArrayNode messages = body.putArray("messages");
        for (LlmMessage message : request.messages()) {
            ObjectNode messageNode = messages.addObject();
            messageNode.put("role", message.role());
            messageNode.put("content", message.content());
        }

        if (request.jsonResponse()) {
            ObjectNode responseFormat = body.putObject("response_format");
            responseFormat.put("type", "json_object");
        }

        String url = normalizeBaseUrl(properties.getBaseUrl()) + "/chat/completions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());

        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        RestClientException lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(
                        url,
                        new HttpEntity<>(body.toString(), headers),
                        String.class
                );
                return parseResponse(response.getBody(), model);
            } catch (HttpStatusCodeException ex) {
                lastError = ex;
                if (!isRetryable(ex) || attempt == attempts) {
                    throw new IllegalStateException(
                            "ProxyAPI error " + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString(),
                            ex
                    );
                }
                log.warn("ProxyAPI retry {}/{} after {}", attempt, attempts, ex.getStatusCode());
            } catch (RestClientException ex) {
                lastError = ex;
                if (attempt == attempts) {
                    throw new IllegalStateException("ProxyAPI request failed: " + ex.getMessage(), ex);
                }
                log.warn("ProxyAPI retry {}/{} after transport error", attempt, attempts);
            }
        }

        throw new IllegalStateException("ProxyAPI request failed", lastError);
    }

    private LlmResponse parseResponse(String body, String model) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = contentNode.isMissingNode() ? "" : contentNode.asText("").trim();
            String responseModel = root.path("model").asText(model);
            return new LlmResponse(content, responseModel);
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось разобрать ответ ProxyAPI: " + ex.getMessage(), ex);
        }
    }

    private String resolveApiKey() {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            return properties.getApiKey();
        }
        String env = System.getenv("PROXYAPI_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return System.getenv("LLM_API_KEY");
    }

    private static boolean isRetryable(HttpStatusCodeException ex) {
        int code = ex.getStatusCode().value();
        return code == 429 || code >= 500;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.proxyapi.ru/openai/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
