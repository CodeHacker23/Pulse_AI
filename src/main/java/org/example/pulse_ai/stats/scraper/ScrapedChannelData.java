package org.example.pulse_ai.stats.scraper;

import java.time.Instant;
import java.util.List;

public record ScrapedChannelData(
        List<ScrapedChannelPost> posts,
        Integer subscriberCount
) {
    public static ScrapedChannelData empty() {
        return new ScrapedChannelData(List.of(), null);
    }
}
