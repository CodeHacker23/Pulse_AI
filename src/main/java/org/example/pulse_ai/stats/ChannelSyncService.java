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

    @Transactional
    public SyncResult syncChannel(ChannelEntity channel) {
        return syncForAnalysis(channel, false);
    }

    /** Быстрый сбор для анализа: кэш, без медленного Bot API, один HTTP-запрос к t.me */
    @Transactional
    public SyncResult syncForAnalysis(ChannelEntity channel) {
        return syncForAnalysis(channel, true);
    }

    private SyncResult syncForAnalysis(ChannelEntity channel, boolean forAnalysis) {
        boolean fast = forAnalysis && analysisProperties.isFastMode();

        if (!fast || !analysisProperties.isSkipBotApiDuringSync()) {
            refreshSubscriberCount(channel);
        }

        int ingested = 0;
        String category = null;

        if (channel.getUsername() != null && !channel.getUsername().isBlank()) {
            // 1) Приоритет — официальный TGStat API (точные просмотры, даты, текст, реакции).
            TgstatApiClient.PostsResult api = tgstatApiClient.getPosts(
                    channel.getUsername(), analysisProperties.getSyncMaxPosts());
            if (api.hasPosts()) {
                category = api.category();
                if (api.subscribers() != null && api.subscribers() > 0) {
                    channel.setSubscriberCount(api.subscribers());
                    channelRepository.save(channel);
                }
                ingested = ingestBatch(channel, api.posts());
                log.info("Sync канал {} через TGStat API: +{} постов", channel.getId(), ingested);
            } else {
                // 2) Фолбэк — публичный скрейпинг t.me/s/.
                int maxPosts = fast ? analysisProperties.getSyncMaxPosts() : Math.max(analysisProperties.getMinPostsFull(), 30);
                int maxPages = fast ? analysisProperties.getSyncMaxPages() : 3;
                int timeoutMs = fast ? analysisProperties.getSyncTimeoutMs() : 20_000;

                ScrapedChannelData data = publicScraper.fetchChannelData(
                        channel.getUsername(), maxPosts, maxPages, timeoutMs);
                if (data.subscriberCount() != null) {
                    channel.setSubscriberCount(data.subscriberCount());
                    channelRepository.save(channel);
                }
                ingested = ingestBatch(channel, data.posts());
            }
        }

        long total = postIngestService.countPosts(channel.getId());
        log.info("Sync канал {}: +{} постов, всего {}", channel.getId(), ingested, total);
        return new SyncResult(ingested, total, category);
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

    public record SyncResult(int newlyFetched, long totalPosts, String category) {
    }
}
