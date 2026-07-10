package org.example.pulse_ai.stats.scraper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelegramPublicChannelScraperTest {

    @Test
    void parseCount_handlesSuffixes() {
        assertEquals(1200, TelegramPublicChannelScraper.parseCount("1.2K"));
        assertEquals(15_300_000, TelegramPublicChannelScraper.parseCount("15.3M"));
        assertEquals(12345, TelegramPublicChannelScraper.parseCount("12 345"));
    }
}
