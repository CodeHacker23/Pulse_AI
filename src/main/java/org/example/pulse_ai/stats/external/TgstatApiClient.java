package org.example.pulse_ai.stats.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseExternalProperties;
import org.example.pulse_ai.stats.scraper.ScrapedChannelPost;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Единый клиент официального TGStat API (https://api.tgstat.ru).
 * Даёт надёжные данные, которых нет в публичном скрейпинге:
 * агрегатную статистику (охват/ERR/ИЦ), точные посты и поиск по нише.
 */
@Slf4j
@Service
public class TgstatApiClient {

    private static final String BASE = "https://api.tgstat.ru";

    private final PulseExternalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TgstatApiClient(PulseExternalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public boolean isEnabled() {
        return properties.isTgstatEnabled();
    }

    /** Агрегатная статистика канала: подписчики, охват, ERR, индекс цитирования. */
    public Optional<ExternalChannelMetrics> getStat(String username) {
        String name = ExternalScrapeSupport.normalizeUsername(username);
        if (!isEnabled() || name.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode r = callOk("/channels/stat", "channelId=" + enc("@" + name));
            if (r == null) {
                return Optional.empty();
            }
            Integer subscribers = intOrNull(r, "participants_count");
            Integer avgReach = intOrNull(r, "avg_post_reach");
            Double err = doubleOrNull(r, "err_percent");
            Double ci = doubleOrNull(r, "ci_index");
            boolean any = subscribers != null || avgReach != null || err != null || ci != null;
            if (!any) {
                return Optional.empty();
            }
            return Optional.of(new ExternalChannelMetrics(
                    "TGStat", true, subscribers, avgReach, err, ci, null, null, null));
        } catch (Exception ex) {
            log.warn("TGStat stat failed для @{}: {}", name, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Точные посты канала (просмотры, текст, дата, реакции/репосты если доступны) + метаданные канала. */
    public PostsResult getPosts(String username, int limit) {
        String name = ExternalScrapeSupport.normalizeUsername(username);
        if (!isEnabled() || name.isBlank()) {
            return PostsResult.empty();
        }
        try {
            int capped = Math.min(Math.max(limit, 1), 50);
            JsonNode r = callOk("/channels/posts",
                    "channelId=" + enc("@" + name) + "&limit=" + capped + "&extended=1&hideDeleted=1");
            if (r == null) {
                return PostsResult.empty();
            }

            JsonNode channel = r.path("channel");
            String category = channel.path("category").asText(null);
            Integer subscribers = intOrNull(channel, "participants_count");
            Double ci = doubleOrNull(channel, "ci_index");

            List<ScrapedChannelPost> posts = new ArrayList<>();
            for (JsonNode item : r.path("items")) {
                if (item.path("is_deleted").asInt(0) == 1) {
                    continue;
                }
                Integer messageId = parseMessageId(item.path("link").asText(null));
                if (messageId == null) {
                    continue;
                }
                long epoch = item.path("date").asLong(0);
                if (epoch <= 0) {
                    continue;
                }
                int views = Math.max(0, item.path("views").asInt(0));
                String text = item.path("text").asText("");
                int reactions = extractReactions(item);
                int forwards = firstInt(item, "forwards", "shares");
                String mediaType = mapMediaType(item.path("media").path("media_type").asText(null));
                JsonNode fwd = item.get("forwarded_from");
                boolean forwarded = fwd != null && !fwd.isNull()
                        && !(fwd.isTextual() && fwd.asText().isBlank());

                posts.add(new ScrapedChannelPost(
                        messageId, text, views, reactions, forwards,
                        Instant.ofEpochSecond(epoch), mediaType, forwarded));
            }
            return new PostsResult(posts, category, subscribers, ci);
        } catch (Exception ex) {
            log.warn("TGStat posts failed для @{}: {}", name, ex.getMessage());
            return PostsResult.empty();
        }
    }

    /**
     * Поиск каналов по строке (ниша / название / ключ). Не требует S+ category-only.
     * Пусто, если API выключен или квота/ошибка.
     */
    public List<NicheComparison.Peer> searchPeers(String query, String excludeUsername, int limit) {
        if (!isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        String me = ExternalScrapeSupport.normalizeUsername(excludeUsername);
        String q = query.trim();
        if (q.length() > 80) {
            q = q.substring(0, 80);
        }
        try {
            JsonNode r = callOk("/channels/search",
                    "q=" + enc(q) + "&country=" + enc("Россия") + "&limit=" + Math.min(Math.max(limit, 5), 50));
            if (r == null) {
                // fallback: иногда ниша передаётся как category
                r = callOk("/channels/search",
                        "category=" + enc(q) + "&country=" + enc("Россия") + "&limit=" + Math.min(Math.max(limit, 5), 50));
            }
            if (r == null) {
                return List.of();
            }
            List<NicheComparison.Peer> peers = new ArrayList<>();
            for (JsonNode item : r.path("items")) {
                if (!"channel".equals(item.path("peer_type").asText())) {
                    continue;
                }
                String uname = item.path("username").asText("").replace("@", "");
                if (uname.isBlank() || uname.equalsIgnoreCase(me)) {
                    continue;
                }
                Integer s = intOrNull(item, "participants_count");
                peers.add(new NicheComparison.Peer(
                        item.path("title").asText(uname),
                        uname,
                        s != null ? s : 0));
                if (peers.size() >= limit) {
                    break;
                }
            }
            return peers;
        } catch (Exception ex) {
            log.warn("TGStat peer search failed для «{}»: {}", q, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Сравнение канала с нишей по категории: медианы подписчиков/ИЦ и топ похожих каналов.
     * Требует тариф S+ (иначе просто вернёт пусто).
     */
    public Optional<NicheComparison> compareNiche(String category, int mySubscribers, String myUsername) {
        if (!isEnabled() || category == null || category.isBlank()) {
            return Optional.empty();
        }
        String me = ExternalScrapeSupport.normalizeUsername(myUsername);
        try {
            JsonNode r = callOk("/channels/search",
                    "category=" + enc(category) + "&country=" + enc("Россия") + "&limit=100");
            if (r == null) {
                return Optional.empty();
            }
            List<Integer> subs = new ArrayList<>();
            List<Double> cis = new ArrayList<>();
            List<NicheComparison.Peer> peers = new ArrayList<>();
            for (JsonNode item : r.path("items")) {
                if (!"channel".equals(item.path("peer_type").asText())) {
                    continue;
                }
                String uname = item.path("username").asText("").replace("@", "");
                if (!uname.isBlank() && uname.equalsIgnoreCase(me)) {
                    continue;
                }
                Integer s = intOrNull(item, "participants_count");
                Double ci = doubleOrNull(item, "ci_index");
                if (s == null) {
                    continue;
                }
                subs.add(s);
                if (ci != null) {
                    cis.add(ci);
                }
                peers.add(new NicheComparison.Peer(
                        item.path("title").asText("Канал"), uname, s));
            }
            if (subs.size() < 3) {
                return Optional.empty();
            }
            int medianSubs = median(subs);
            double medianCi = cis.isEmpty() ? 0 : medianD(cis);
            long biggerThan = subs.stream().filter(s -> mySubscribers > s).count();
            int percentile = (int) Math.round(biggerThan * 100.0 / subs.size());

            peers.sort((a, b) -> Integer.compare(
                    Math.abs(a.subscribers() - mySubscribers), Math.abs(b.subscribers() - mySubscribers)));
            List<NicheComparison.Peer> similar = peers.stream().limit(3).toList();

            return Optional.of(new NicheComparison(
                    category, subs.size(), medianSubs, medianCi, percentile, similar));
        } catch (Exception ex) {
            log.warn("TGStat search failed для ниши {}: {}", category, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Динамика подписчиков по дням (последние {@code days} дней). Требует тариф S+.
     * Возвращает точки по возрастанию даты; пусто, если недоступно.
     */
    public List<SubscriberPoint> getSubscriberSeries(String username, int days) {
        String name = ExternalScrapeSupport.normalizeUsername(username);
        if (!isEnabled() || name.isBlank()) {
            return List.of();
        }
        try {
            long endTs = Instant.now().getEpochSecond();
            long startTs = endTs - (long) Math.max(days, 1) * 86_400L;
            JsonNode response = callOk("/channels/subscribers",
                    "channelId=" + enc("@" + name) + "&group=day"
                            + "&startDate=" + startTs + "&endDate=" + endTs);
            if (response == null || !response.isArray()) {
                return List.of();
            }
            List<SubscriberPoint> points = new ArrayList<>();
            for (JsonNode item : response) {
                String period = item.path("period").asText(null);
                int count = item.path("participants_count").asInt(0);
                if (period == null || count <= 0) {
                    continue;
                }
                try {
                    points.add(new SubscriberPoint(java.time.LocalDate.parse(period), count));
                } catch (Exception ignored) {
                    // пропускаем некорректные даты
                }
            }
            points.sort(java.util.Comparator.comparing(SubscriberPoint::date));
            return points;
        } catch (Exception ex) {
            log.warn("TGStat subscribers failed для @{}: {}", name, ex.getMessage());
            return List.of();
        }
    }

    // ---- HTTP ----

    private JsonNode callOk(String path, String query) throws Exception {
        String url = BASE + path + "?token=" + enc(properties.getTgstatToken()) + "&" + query;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(properties.getTgstatTimeoutMs()))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("TGStat {} HTTP {}", path, response.statusCode());
            return null;
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (!"ok".equals(root.path("status").asText())) {
            log.warn("TGStat {} error: {}", path, root.path("error").asText("unknown"));
            return null;
        }
        return root.path("response");
    }

    // ---- helpers ----

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static Integer parseMessageId(String link) {
        if (link == null) {
            return null;
        }
        int slash = link.lastIndexOf('/');
        if (slash < 0 || slash == link.length() - 1) {
            return null;
        }
        try {
            long id = Long.parseLong(link.substring(slash + 1).trim());
            return id > 0 && id <= Integer.MAX_VALUE ? (int) id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int extractReactions(JsonNode item) {
        JsonNode reactions = item.get("reactions");
        if (reactions == null || reactions.isNull()) {
            return 0;
        }
        if (reactions.isNumber()) {
            return Math.max(0, reactions.asInt());
        }
        int total = reactions.path("total").asInt(reactions.path("count").asInt(0));
        return Math.max(0, total);
    }

    private static int firstInt(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.canConvertToInt()) {
                return Math.max(0, v.asInt());
            }
        }
        return 0;
    }

    private static String mapMediaType(String tgstatType) {
        if (tgstatType == null) {
            return "text";
        }
        String t = tgstatType.toLowerCase();
        if (t.contains("photo")) {
            return "photo";
        }
        if (t.contains("video")) {
            return "video";
        }
        if (t.contains("poll")) {
            return "poll";
        }
        if (t.contains("audio") || t.contains("voice")) {
            return "audio";
        }
        if (t.contains("document")) {
            return "document";
        }
        return "text";
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.canConvertToInt() && v.asInt() > 0 ? v.asInt() : null;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() && v.asDouble() > 0 ? v.asDouble() : null;
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    private static double medianD(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /** Точка динамики подписчиков. */
    public record SubscriberPoint(java.time.LocalDate date, int count) {
    }

    /** Посты канала + метаданные из TGStat. */
    public record PostsResult(List<ScrapedChannelPost> posts, String category, Integer subscribers, Double ciIndex) {
        public static PostsResult empty() {
            return new PostsResult(List.of(), null, null, null);
        }

        public boolean hasPosts() {
            return posts != null && !posts.isEmpty();
        }
    }
}
