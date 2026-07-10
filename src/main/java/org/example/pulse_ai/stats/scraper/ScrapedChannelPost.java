package org.example.pulse_ai.stats.scraper;

import java.time.Instant;

public record ScrapedChannelPost(
        int messageId,
        String text,
        int views,
        int reactions,
        int forwards,
        Instant publishedAt,
        String mediaType,
        boolean forwarded
) {
}
