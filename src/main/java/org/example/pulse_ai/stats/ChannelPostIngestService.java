package org.example.pulse_ai.stats;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.example.pulse_ai.persistence.repository.ChannelPostRepository;
import org.example.pulse_ai.stats.scraper.ScrapedChannelPost;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChannelPostIngestService {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final ChannelPostRepository channelPostRepository;

    @Transactional
    public ChannelPostEntity ingestFromMessage(ChannelEntity channel, Message message) {
        Integer messageId = resolveMessageId(message);
        if (messageId == null) {
            return null;
        }

        Optional<ChannelPostEntity> existing =
                channelPostRepository.findByChannelIdAndTelegramMessageId(channel.getId(), messageId);
        ChannelPostEntity post = existing.orElseGet(ChannelPostEntity::new);

        String fullText = extractText(message);
        int views = estimateViews(channel, fullText, messageId);
        int reactions = 0;
        int forwards = message.getForwardFromChat() != null ? 1 : 0;

        post.setChannelId(channel.getId());
        post.setTelegramMessageId(messageId);
        post.setPublishedAt(resolvePublishedAt(message));
        post.setFullText(fullText);
        post.setTextPreview(truncate(fullText, 500));
        post.setViews(views);
        post.setForwards(forwards);
        post.setReactionsTotal(reactions);
        post.setRepliesCount(0);
        post.setEngagementRate(calculateEr(views, reactions, forwards, 0));
        post.setMediaType(resolveMediaType(message));
        post.setForwarded(message.getForwardFromChat() != null);

        return channelPostRepository.save(post);
    }

    @Transactional
    public ChannelPostEntity ingestFromScraped(ChannelEntity channel, ScrapedChannelPost scraped) {
        ChannelPostEntity post = buildFromScraped(channel, scraped);
        return post != null ? channelPostRepository.save(post) : null;
    }

    public ChannelPostEntity buildFromScraped(ChannelEntity channel, ScrapedChannelPost scraped) {
        Optional<ChannelPostEntity> existing =
                channelPostRepository.findByChannelIdAndTelegramMessageId(channel.getId(), scraped.messageId());
        ChannelPostEntity post = existing.orElseGet(ChannelPostEntity::new);

        int reactions = Math.max(0, scraped.reactions());
        int forwards = Math.max(0, scraped.forwards());

        post.setChannelId(channel.getId());
        post.setTelegramMessageId(scraped.messageId());
        post.setPublishedAt(scraped.publishedAt());
        post.setFullText(scraped.text());
        post.setTextPreview(truncate(scraped.text(), 500));
        post.setViews(scraped.views());
        post.setForwards(forwards);
        post.setReactionsTotal(reactions);
        post.setRepliesCount(0);
        post.setEngagementRate(calculateEr(scraped.views(), reactions, forwards, 0));
        post.setMediaType(scraped.mediaType());
        post.setForwarded(scraped.forwarded());
        return post;
    }

    @Transactional
    public void saveAll(List<ChannelPostEntity> posts) {
        if (posts == null || posts.isEmpty()) {
            return;
        }
        channelPostRepository.saveAll(posts);
    }

    public long countPosts(Long channelId) {
        return channelPostRepository.countByChannelId(channelId);
    }

    private static Integer resolveMessageId(Message message) {
        if (message.getForwardFromMessageId() != null) {
            return message.getForwardFromMessageId();
        }
        return message.getMessageId();
    }

    private static Instant resolvePublishedAt(Message message) {
        if (message.getForwardDate() != null) {
            return Instant.ofEpochSecond(message.getForwardDate());
        }
        if (message.getDate() != null) {
            return Instant.ofEpochSecond(message.getDate());
        }
        return Instant.now();
    }

    private static String extractText(Message message) {
        if (message.getText() != null && !message.getText().isBlank()) {
            return message.getText().trim();
        }
        if (message.getCaption() != null && !message.getCaption().isBlank()) {
            return message.getCaption().trim();
        }
        return "Пост без текста";
    }

    private static int estimateViews(ChannelEntity channel, String text, int messageId) {
        int subscribers = channel.getSubscriberCount() != null && channel.getSubscriberCount() > 0
                ? channel.getSubscriberCount()
                : 3_000;
        double textFactor = Math.min(1.3, 0.7 + text.length() / 2000.0);
        double variance = 0.75 + (Math.abs(messageId) % 50) / 100.0;
        return (int) Math.max(150, subscribers * 0.09 * textFactor * variance);
    }

    private static BigDecimal calculateEr(int views, int reactions, int forwards, int replies) {
        if (views <= 0) {
            return BigDecimal.ZERO;
        }
        double er = (reactions + forwards + replies) * 100.0 / views;
        return BigDecimal.valueOf(er).setScale(2, RoundingMode.HALF_UP);
    }

    private static String resolveMediaType(Message message) {
        if (message.hasPhoto()) {
            return "photo";
        }
        if (message.hasVideo()) {
            return "video";
        }
        if (message.hasVoice() || message.hasAudio()) {
            return "audio";
        }
        if (message.hasDocument()) {
            return "document";
        }
        if (message.hasPoll()) {
            return "poll";
        }
        return "text";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }
}
