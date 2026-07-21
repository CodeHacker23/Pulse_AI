package org.example.pulse_ai.domain.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.example.pulse_ai.persistence.entity.PublishedPostEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.ChannelPostRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.PublishedPostRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.stats.ChannelSyncService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Обучение на охватах: через сутки после публикации дочитывает фактические просмотры,
 * сравнивает со средним по каналу, копит статистику слотов и сообщает результат владельцу.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostPerformanceTracker {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final long MEASURE_AFTER_HOURS = 24;
    private static final long GIVE_UP_AFTER_HOURS = 120;

    private final PublishedPostRepository publishedPostRepository;
    private final ChannelPostRepository channelPostRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final ChannelSyncService channelSyncService;
    private final SlotPerformanceService slotPerformanceService;
    private final TelegramMessageSender messageSender;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void measureDuePosts() {
        Instant now = Instant.now();
        Instant to = now.minus(MEASURE_AFTER_HOURS, ChronoUnit.HOURS);
        Instant from = now.minus(GIVE_UP_AFTER_HOURS, ChronoUnit.HOURS);
        List<PublishedPostEntity> due = publishedPostRepository
                .findByPerfMeasuredFalseAndPublishedAtBetween(from, to);
        if (due.isEmpty()) {
            return;
        }
        log.info("Performance tracker: {} post(s) to measure", due.size());

        Set<Long> synced = new HashSet<>();
        for (PublishedPostEntity post : due) {
            try {
                measure(post, synced);
            } catch (Exception ex) {
                log.warn("Measure post {} failed: {}", post.getId(), ex.getMessage());
            }
        }

        // Слишком старые, но так и не найденные — закрываем, чтобы не крутились вечно.
        List<PublishedPostEntity> stale = publishedPostRepository
                .findByPerfMeasuredFalseAndPublishedAtBetween(Instant.EPOCH, from);
        for (PublishedPostEntity post : stale) {
            post.setPerfMeasured(true);
            publishedPostRepository.save(post);
        }
    }

    private void measure(PublishedPostEntity post, Set<Long> synced) {
        ChannelEntity channel = channelRepository.findById(post.getChannelId()).orElse(null);
        if (channel == null || post.getTelegramMessageId() == null) {
            post.setPerfMeasured(true);
            publishedPostRepository.save(post);
            return;
        }

        if (synced.add(channel.getId())) {
            try {
                channelSyncService.syncChannel(channel);
            } catch (Exception ex) {
                log.debug("Sync channel {} for measure failed: {}", channel.getId(), ex.getMessage());
            }
        }

        ChannelPostEntity scraped = channelPostRepository
                .findByChannelIdAndTelegramMessageId(channel.getId(), post.getTelegramMessageId())
                .orElse(null);
        if (scraped == null || scraped.getViews() == null || scraped.getViews() <= 0) {
            // Ещё не подтянулись просмотры — попробуем на следующем прогоне (пока не устареет).
            return;
        }

        int actual = scraped.getViews();
        double baseline = channelAverageViews(channel.getId(), post.getId());
        double ratio = baseline > 0 ? actual / baseline : 1.0;
        String slotKey = SlotPerformanceService.slotKey(post.getPublishedAt());
        slotPerformanceService.record(channel.getId(), slotKey, ratio);

        post.setPerfMeasured(true);
        post.setPerfViews(actual);
        post.setPerfMeasuredAt(Instant.now());
        publishedPostRepository.save(post);

        notifyOwner(post, channel, actual, baseline, ratio);
    }

    private double channelAverageViews(Long channelId, Long excludePublishedId) {
        List<ChannelPostEntity> posts = channelPostRepository.findByChannelIdOrderByPublishedAtAsc(channelId);
        int start = Math.max(0, posts.size() - 50);
        long sum = 0;
        int count = 0;
        for (int i = start; i < posts.size(); i++) {
            Integer v = posts.get(i).getViews();
            if (v != null && v > 0) {
                sum += v;
                count++;
            }
        }
        return count == 0 ? 0 : (double) sum / count;
    }

    private void notifyOwner(PublishedPostEntity post, ChannelEntity channel, int actual, double baseline, double ratio) {
        UserEntity user = userRepository.findById(post.getUserId()).orElse(null);
        if (user == null || user.getTelegramId() == null) {
            return;
        }
        String when = HUMAN.format(ZonedDateTime.ofInstant(post.getPublishedAt(), MSK));
        int avg = (int) Math.round(baseline);
        StringBuilder sb = new StringBuilder();
        if (ratio >= 1.1) {
            sb.append("📈 <b>Пост сработал выше среднего</b>\n\n");
            sb.append("Публикация от ").append(when).append(" (МСК) набрала <b>")
                    .append(actual).append("</b> просмотров");
            if (avg > 0) {
                sb.append(" — это выше вашей средней (~").append(avg).append(")");
            }
            sb.append(".\n\nЗапомнил: это время хорошо заходит и буду чаще предлагать его.");
        } else if (ratio <= 0.85 && avg > 0) {
            sb.append("📉 <b>Охват ниже обычного</b>\n\n");
            sb.append("Публикация от ").append(when).append(" (МСК) набрала <b>")
                    .append(actual).append("</b> просмотров при средней ~").append(avg).append(".\n\n");
            sb.append("Учту это в рекомендациях времени — попробуем другой слот для следующих постов.");
        } else {
            sb.append("📊 <b>Замер охвата</b>\n\n");
            sb.append("Публикация от ").append(when).append(" (МСК): <b>").append(actual)
                    .append("</b> просмотров");
            if (avg > 0) {
                sb.append(" (средняя ~").append(avg).append(")");
            }
            sb.append(". Данные учтены для подбора лучшего времени.");
        }
        messageSender.sendTextSafe(user.getTelegramId(), sb.toString());
    }
}
