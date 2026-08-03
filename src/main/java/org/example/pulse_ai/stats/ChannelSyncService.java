package org.example.pulse_ai.stats;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.stats.external.TgstatApiClient;
import org.example.pulse_ai.stats.scraper.ScrapedChannelData;
import org.example.pulse_ai.stats.scraper.ScrapedChannelPost;
import org.example.pulse_ai.stats.scraper.TelegramPublicChannelScraper;
import org.example.pulse_ai.telegram.TelegramBotApiService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelSyncService {

    private final ChannelRepository channelRepository;
    private final ChannelPostIngestService postIngestService;
    private final TelegramPublicChannelScraper publicScraper;
    private final TelegramBotApiService botApi;
    private final PulseAnalysisProperties analysisProperties;
    private final TgstatApiClient tgstatApiClient;
    private final AnalyticsService analyticsService;

    @Transactional
    public SyncResult syncChannel(ChannelEntity channel) {
        return syncInternal(channel, false, true);
    }

    /** Быстрый сбор для анализа: кэш, без медленного Bot API, один HTTP-запрос к t.me */
    @Transactional
    public SyncResult syncForAnalysis(ChannelEntity channel) {
        return syncForAnalysis(channel, true);
    }

    /**
     * @param useTgstat false — только scrape t.me (бесплатный слой); true — TGStat API если token есть
     */
    @Transactional
    public SyncResult syncForAnalysis(ChannelEntity channel, boolean useTgstat) {
        return syncInternal(channel, true, useTgstat);
    }

    private SyncResult syncInternal(ChannelEntity channel, boolean forAnalysis, boolean useTgstat) {
        boolean fast = forAnalysis && analysisProperties.isFastMode();

        // Подписчики: Bot API — источник правды, если бот в канале. Даже в fast-mode.
        Integer botApiSubs = null;
        if (channel.getTelegramChatId() != null) {
            botApiSubs = botApi.getMemberCount(channel.getTelegramChatId()).orElse(null);
            if (botApiSubs != null && botApiSubs > 0) {
                channel.setSubscriberCount(botApiSubs);
                channelRepository.save(channel);
            }
        } else if (!fast || !analysisProperties.isSkipBotApiDuringSync()) {
            refreshSubscriberCount(channel);
        }

        int ingested = 0;
        String category = null;

        if (channel.getUsername() != null && !channel.getUsername().isBlank()) {
            boolean usedApi = false;
            if (useTgstat) {
                TgstatApiClient.PostsResult api = tgstatApiClient.getPosts(
                        channel.getUsername(), analysisProperties.getSyncMaxPosts());
                if (api.hasPosts()) {
                    category = api.category();
                    applyTrustedSubscriberCount(channel, api.subscribers(), botApiSubs);
                    ingested = ingestBatch(channel, api.posts());
                    usedApi = true;
                    log.info("Sync канал {} через TGStat API: +{} постов", channel.getId(), ingested);
                }
            }
            if (!usedApi) {
                int maxPosts = fast ? analysisProperties.getSyncMaxPosts() : Math.max(analysisProperties.getMinPostsFull(), 30);
                int maxPages = fast ? analysisProperties.getSyncMaxPages() : 3;
                int timeoutMs = fast ? analysisProperties.getSyncTimeoutMs() : 20_000;

                ScrapedChannelData data = publicScraper.fetchChannelData(
                        channel.getUsername(), maxPosts, maxPages, timeoutMs);
                applyTrustedSubscriberCount(channel, data.subscriberCount(), botApiSubs);
                ingested = ingestBatch(channel, data.posts());
                if (!useTgstat) {
                    log.info("Sync канал {} через t.me scrape (без TGStat): +{} постов", channel.getId(), ingested);
                }
            }
        }

        long total = postIngestService.countPosts(channel.getId());
        int sanitized = analyticsService.sanitizeChannelPosts(channel.getId());
        if (sanitized > 0) {
            log.info("Sync канал {}: обнулено {} постов с невозможными просмотрами", channel.getId(), sanitized);
        }
        log.info("Sync канал {}: +{} постов, всего {}", channel.getId(), ingested, total);
        return new SyncResult(ingested, total, category, sanitized);
    }

    /**
     * Не даём TGStat/telega/парсеру затереть Bot API цифру на микроканале
     * (типичный баг: чужой «7.9K» вместо реальных 6).
     */
    private void applyTrustedSubscriberCount(ChannelEntity channel, Integer candidate, Integer botApiSubs) {
        if (candidate == null || candidate <= 0) {
            return;
        }
        int trustedFloor = botApiSubs != null && botApiSubs > 0
                ? botApiSubs
                : (channel.getSubscriberCount() != null ? channel.getSubscriberCount() : 0);
        if (trustedFloor > 0 && trustedFloor <= 500 && candidate > trustedFloor * 5L) {
            log.warn("Игнор подписчиков {} для @{}: есть доверенные {} (Bot API/БД)",
                    candidate, channel.getUsername(), trustedFloor);
            return;
        }
        if (botApiSubs != null && botApiSubs > 0) {
            // Bot API уже записан — внешние источники не перетирают.
            return;
        }
        channel.setSubscriberCount(candidate);
        channelRepository.save(channel);
    }

    private int ingestBatch(ChannelEntity channel, List<ScrapedChannelPost> posts) {
        List<ChannelPostEntity> batch = new ArrayList<>();
        for (ScrapedChannelPost scrapedPost : posts) {
            ChannelPostEntity entity = postIngestService.buildFromScraped(channel, scrapedPost);
            if (entity != null) {
                batch.add(entity);
            }
        }
        postIngestService.saveAll(batch);
        return batch.size();
    }

    @Transactional
    public void ingestLiveChannelPost(Message message) {
        if (message.getChat() == null || !"channel".equals(message.getChat().getType())) {
            return;
        }

        channelRepository.findByTelegramChatId(message.getChat().getId()).ifPresent(channel -> {
            postIngestService.ingestFromMessage(channel, message);
            log.debug("Live post ingested: channelId={}, messageId={}", channel.getId(), message.getMessageId());
        });
    }

    @Transactional
    public void refreshSubscriberCount(ChannelEntity channel) {
        if (channel.getTelegramChatId() != null) {
            botApi.getMemberCount(channel.getTelegramChatId()).ifPresent(count -> {
                channel.setSubscriberCount(count);
                channelRepository.save(channel);
            });
        } else if (channel.getUsername() != null) {
            Integer scraped = publicScraper.fetchSubscriberCount(channel.getUsername());
            if (scraped != null) {
                channel.setSubscriberCount(scraped);
                channelRepository.save(channel);
            }
        }
    }

    public record SyncResult(int newlyFetched, long totalPosts, String category, int sanitizedPosts) {
        public SyncResult(int newlyFetched, long totalPosts, String category) {
            this(newlyFetched, totalPosts, category, 0);
        }
    }
}
