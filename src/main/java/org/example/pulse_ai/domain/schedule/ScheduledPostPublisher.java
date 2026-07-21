package org.example.pulse_ai.domain.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.analysis.GeneratedPostService;
import org.example.pulse_ai.domain.publish.ChannelPublishService;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.GeneratedPostEntity;
import org.example.pulse_ai.persistence.entity.ScheduledPostEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.ScheduledPostRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Раз в минуту публикует посты, у которых наступило запланированное время.
 * Гейтится реальными записями PENDING, поэтому без запланированных постов ничего не делает.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledPostPublisher {

    private final ScheduledPostRepository repository;
    private final ChannelPublishService publishService;
    private final GeneratedPostService generatedPostService;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final TelegramMessageSender messageSender;

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void publishDue() {
        List<ScheduledPostEntity> due = repository
                .findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                        ScheduledPostStatus.PENDING, Instant.now());
        if (due.isEmpty()) {
            return;
        }
        log.info("Scheduled publisher: {} post(s) due", due.size());
        for (ScheduledPostEntity entity : due) {
            try {
                process(entity);
            } catch (Exception ex) {
                log.warn("Scheduled post {} failed: {}", entity.getId(), ex.getMessage());
                markFailed(entity, "внутренняя ошибка");
            }
        }
    }

    private void process(ScheduledPostEntity entity) {
        UserEntity user = userRepository.findById(entity.getUserId()).orElse(null);
        ChannelEntity channel = channelRepository.findById(entity.getChannelId()).orElse(null);
        GeneratedPostEntity post = generatedPostService.findById(entity.getGeneratedPostId()).orElse(null);
        if (user == null || channel == null || post == null) {
            markFailed(entity, "пост или канал не найдены");
            return;
        }

        if ("POLL".equalsIgnoreCase(entity.getContentType())) {
            post.setContentType("POLL");
            post.setPollOptions(entity.getPollOptions());
            post.setPollAnonymous(entity.isPollAnonymous());
        }

        ChannelPublishService.PublishResult result = publishService.publish(
                user, channel, post, entity.getFinalText(), entity.getImageUrl());

        if (result.success()) {
            entity.setStatus(ScheduledPostStatus.PUBLISHED);
            entity.setPublishedMessageId(result.messageId());
            entity.setPostLink(result.link());
            repository.save(entity);
            String kind = "POLL".equalsIgnoreCase(entity.getContentType()) ? "опрос" : "пост";
            notifyOwner(user.getTelegramId(),
                    "✅ <b>Запланированный " + kind + " опубликован</b>\n\n"
                            + (result.link() != null
                                    ? "🔗 <a href=\"" + TgHtml.esc(result.link()) + "\">Открыть</a>"
                                    : "В канале «" + TgHtml.esc(channel.getTitle()) + "»."));
        } else {
            markFailedAndNotify(entity, user.getTelegramId(), channel.getTitle(), result.error());
        }
    }

    private void markFailed(ScheduledPostEntity entity, String reason) {
        entity.setStatus(ScheduledPostStatus.FAILED);
        repository.save(entity);
        log.warn("Scheduled post {} marked FAILED: {}", entity.getId(), reason);
    }

    private void markFailedAndNotify(ScheduledPostEntity entity, Long telegramId, String channelTitle, String error) {
        entity.setStatus(ScheduledPostStatus.FAILED);
        repository.save(entity);
        notifyOwner(telegramId,
                "⚠️ <b>Не удалось опубликовать запланированный пост</b>\n\n"
                        + "Канал: «" + TgHtml.esc(channelTitle) + "»\n"
                        + (error != null ? TgHtml.esc(error) : "Проверьте права бота в канале.")
                        + "\n\nОткройте черновик и опубликуйте вручную.");
    }

    private void notifyOwner(Long telegramId, String text) {
        if (telegramId == null) {
            return;
        }
        messageSender.sendTextSafe(telegramId, text);
    }
}
