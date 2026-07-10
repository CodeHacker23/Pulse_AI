package org.example.pulse_ai.stats.scraper;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TelegramPublicChannelScraper {

    private static final Pattern POST_ID = Pattern.compile("/(\\d+)$");

    public List<ScrapedChannelPost> fetchRecentPosts(String username, int maxPosts) {
        return fetchChannelData(username, maxPosts, 3, 20_000).posts();
    }

    public ScrapedChannelData fetchChannelData(String username, int maxPosts, int maxPages, int timeoutMs) {
        if (username == null || username.isBlank()) {
            return ScrapedChannelData.empty();
        }

        String normalized = username.startsWith("@") ? username.substring(1) : username;
        Map<Integer, ScrapedChannelPost> posts = new LinkedHashMap<>();
        String before = null;
        Integer subscribers = null;

        try {
            for (int page = 0; page < maxPages && posts.size() < maxPosts; page++) {
                String url = before == null
                        ? "https://t.me/s/" + normalized
                        : "https://t.me/s/" + normalized + "?before=" + before;

                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (compatible; ChannelPulseBot/1.0)")
                        .timeout(timeoutMs)
                        .get();

                if (subscribers == null) {
                    subscribers = parseSubscriberCount(doc);
                }

                Elements messages = doc.select("div.tgme_widget_message");
                if (messages.isEmpty()) {
                    break;
                }

                Integer oldestOnPage = null;
                for (Element message : messages) {
                    ScrapedChannelPost post = parseMessage(message);
                    if (post != null) {
                        posts.putIfAbsent(post.messageId(), post);
                        // t.me/s/ отдаёт посты от старых к новым; для страницы ?before= нужен САМЫЙ СТАРЫЙ id
                        if (oldestOnPage == null || post.messageId() < oldestOnPage) {
                            oldestOnPage = post.messageId();
                        }
                    }
                }

                if (oldestOnPage == null || String.valueOf(oldestOnPage).equals(before)) {
                    break;
                }
                before = String.valueOf(oldestOnPage);
            }
        } catch (IOException ex) {
            log.warn("Не удалось загрузить посты канала @{}: {}", normalized, ex.getMessage());
            return new ScrapedChannelData(List.of(), subscribers);
        }

        List<ScrapedChannelPost> limited = posts.values().stream().limit(maxPosts).toList();
        return new ScrapedChannelData(limited, subscribers);
    }

    public Integer fetchSubscriberCount(String username) {
        return fetchChannelData(username, 1, 1, 8_000).subscriberCount();
    }

    private static Integer parseSubscriberCount(Document doc) {
        Element counter = doc.selectFirst("div.tgme_channel_info_counter");
        if (counter == null) {
            return null;
        }
        String value = counter.selectFirst("span.counter_value") != null
                ? counter.selectFirst("span.counter_value").text()
                : counter.text();
        return (int) parseCount(value);
    }

    private ScrapedChannelPost parseMessage(Element message) {
        String dataPost = message.attr("data-post");
        if (dataPost.isBlank()) {
            return null;
        }

        Matcher matcher = POST_ID.matcher(dataPost);
        if (!matcher.find()) {
            return null;
        }
        int messageId = Integer.parseInt(matcher.group(1));

        Element textEl = message.selectFirst("div.tgme_widget_message_text");
        String text = textEl != null ? textEl.text().trim() : "";
        if (text.isBlank()) {
            text = detectMediaLabel(message);
        }

        Element viewsEl = message.selectFirst("span.tgme_widget_message_views");
        int views = viewsEl != null ? (int) parseCount(viewsEl.text()) : 0;

        int reactions = parseReactions(message);
        int forwards = parseForwards(message);

        Instant publishedAt = Instant.now();
        Element timeEl = message.selectFirst("time");
        if (timeEl != null && timeEl.hasAttr("datetime")) {
            try {
                publishedAt = OffsetDateTime.parse(timeEl.attr("datetime")).toInstant();
            } catch (Exception ignored) {
                // keep now
            }
        }

        boolean forwarded = message.selectFirst("div.tgme_widget_message_forwarded_from") != null;
        return new ScrapedChannelPost(messageId, text, views, reactions, forwards, publishedAt,
                detectMediaType(message), forwarded);
    }

    private static int parseReactions(Element message) {
        int total = 0;
        for (Element reaction : message.select("span.tgme_widget_message_reaction")) {
            Element counter = reaction.selectFirst("span.tgme_reaction_count");
            String value = counter != null ? counter.text() : reaction.text();
            total += (int) parseCount(value);
        }
        return total;
    }

    private static int parseForwards(Element message) {
        Element forwardsEl = message.selectFirst("span.tgme_widget_message_forwards_count, span.tgme_widget_message_forwarded_from_name");
        if (forwardsEl == null) {
            return 0;
        }
        return (int) parseCount(forwardsEl.text());
    }

    private static String detectMediaType(Element message) {
        if (!message.select("a.tgme_widget_message_photo_wrap").isEmpty()) {
            return "photo";
        }
        if (!message.select("a.tgme_widget_message_video_wrap").isEmpty()) {
            return "video";
        }
        if (!message.select("a.tgme_widget_message_voice").isEmpty()) {
            return "audio";
        }
        if (!message.select("a.tgme_widget_message_document_wrap").isEmpty()) {
            return "document";
        }
        if (!message.select("div.tgme_widget_message_poll").isEmpty()) {
            return "poll";
        }
        return "text";
    }

    private static String detectMediaLabel(Element message) {
        return switch (detectMediaType(message)) {
            case "photo" -> "[Фото]";
            case "video" -> "[Видео]";
            case "audio" -> "[Аудио]";
            case "document" -> "[Документ]";
            case "poll" -> "[Опрос]";
            default -> "[Пост без текста]";
        };
    }

    /** Парсит "1.2K", "15.3M", "12 345" */
    static long parseCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String normalized = raw.trim().replace(" ", "").replace("\u00a0", "");
        try {
            if (normalized.endsWith("K") || normalized.endsWith("k")) {
                return Math.round(Double.parseDouble(normalized.substring(0, normalized.length() - 1)) * 1_000);
            }
            if (normalized.endsWith("M") || normalized.endsWith("m")) {
                return Math.round(Double.parseDouble(normalized.substring(0, normalized.length() - 1)) * 1_000_000);
            }
            return Long.parseLong(normalized.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
