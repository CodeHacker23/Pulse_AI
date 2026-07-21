package org.example.pulse_ai.domain.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseImageProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Подбирает фото к посту: LLM формулирует визуальный поисковый запрос,
 * Pexels отдаёт лицензионное фото (коммерческое использование разрешено).
 *
 * Не всякому посту нужно фото — LLM сам решает, уместно ли изображение,
 * и возвращает пустой результат, если пост чисто текстовый.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostImageService {

    private static final String SYSTEM = """
            Ты — арт-директор Telegram-канала. По тексту поста решаешь, нужна ли ему картинка,
            и если да — формулируешь короткий ПОИСКОВЫЙ ЗАПРОС для фотостока (Pexels).

            Правила:
            - Запрос на английском, 2–4 слова, конкретный визуальный образ (не абстракция).
            - Фото должно усиливать смысл поста и цеплять взгляд в ленте.
            - Если пост чисто служебный/новостной/личный и фото будет лишним — верни needsImage=false.
            Ответ строго JSON: {"needsImage": true|false, "query": "...", "reason": "кратко по-русски"}""";

    private final PulseImageProperties properties;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * Подбирает фото для поста. Возвращает пусто, если провайдер не настроен,
     * LLM решил, что фото не нужно, или сток ничего не нашёл.
     */
    public Optional<ImageSuggestion> suggestForPost(String postText, String channelTitle) {
        if (!properties.isConfigured()) {
            return Optional.empty();
        }
        Query query = resolveQuery(postText, channelTitle);
        if (query == null || !query.needsImage() || query.query() == null || query.query().isBlank()) {
            return Optional.empty();
        }
        List<ImageSuggestion> candidates = searchPexels(query.query());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(0));
    }

    /**
     * Возвращает случайное фото по тому же посту — для кнопки «Другое фото».
     */
    public Optional<ImageSuggestion> anotherForPost(String postText, String channelTitle) {
        if (!properties.isConfigured()) {
            return Optional.empty();
        }
        Query query = resolveQuery(postText, channelTitle);
        if (query == null || query.query() == null || query.query().isBlank()) {
            return Optional.empty();
        }
        List<ImageSuggestion> candidates = searchPexels(query.query());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int idx = ThreadLocalRandom.current().nextInt(candidates.size());
        return Optional.of(candidates.get(idx));
    }

    private Query resolveQuery(String postText, String channelTitle) {
        String user = """
                Канал: %s

                Текст поста:
                %s
                """.formatted(channelTitle, trim(postText, 1500));
        try {
            String json = llmService.completeJsonWithTimeout(SYSTEM, user, 20);
            JsonNode node = objectMapper.readTree(json);
            boolean needs = node.path("needsImage").asBoolean(false);
            String q = node.path("query").asText("").trim();
            return new Query(needs, q);
        } catch (Exception ex) {
            log.warn("Image query LLM failed: {}", ex.getMessage());
            return null;
        }
    }

    private List<ImageSuggestion> searchPexels(String query) {
        List<ImageSuggestion> result = new ArrayList<>();
        try {
            String url = "https://api.pexels.com/v1/search?query="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&per_page=" + Math.max(1, properties.getCandidatesPerQuery())
                    + "&orientation=landscape";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                    .header("Authorization", properties.getPexelsApiKey())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Pexels responded {} for query '{}'", response.statusCode(), query);
                return result;
            }
            JsonNode photos = objectMapper.readTree(response.body()).path("photos");
            for (JsonNode photo : photos) {
                JsonNode src = photo.path("src");
                String imageUrl = firstNonBlank(
                        src.path("large2x").asText(null),
                        src.path("large").asText(null),
                        src.path("original").asText(null));
                if (imageUrl == null) {
                    continue;
                }
                String author = photo.path("photographer").asText("Pexels");
                String pageUrl = photo.path("url").asText(null);
                result.add(new ImageSuggestion(imageUrl, query, author, pageUrl));
            }
        } catch (Exception ex) {
            log.warn("Pexels search failed for '{}': {}", query, ex.getMessage());
        }
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record Query(boolean needsImage, String query) {
    }

    /** Найденное фото: прямой URL картинки, поисковый запрос, автор и страница-источник. */
    public record ImageSuggestion(String imageUrl, String query, String author, String sourceUrl) {
    }
}
