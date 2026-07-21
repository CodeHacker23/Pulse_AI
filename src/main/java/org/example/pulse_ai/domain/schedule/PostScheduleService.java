package org.example.pulse_ai.domain.schedule;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.ScheduledPostEntity;
import org.example.pulse_ai.persistence.repository.ScheduledPostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Планировщик постов: постановка в очередь, список и отмена отложенных публикаций.
 * Саму публикацию по времени выполняет {@link ScheduledPostPublisher}.
 */
@Service
@RequiredArgsConstructor
public class PostScheduleService {

    private final ScheduledPostRepository repository;

    @Transactional
    public ScheduledPostEntity schedule(
            long userId,
            long channelId,
            long generatedPostId,
            String finalText,
            String imageUrl,
            Instant when
    ) {
        return schedule(userId, channelId, generatedPostId, finalText, imageUrl, when, "TEXT", null, false);
    }

    @Transactional
    public ScheduledPostEntity schedule(
            long userId,
            long channelId,
            long generatedPostId,
            String finalText,
            String imageUrl,
            Instant when,
            String contentType,
            String pollOptions,
            boolean pollAnonymous
    ) {
        ScheduledPostEntity entity = new ScheduledPostEntity();
        entity.setUserId(userId);
        entity.setChannelId(channelId);
        entity.setGeneratedPostId(generatedPostId);
        entity.setFinalText(finalText);
        entity.setImageUrl(imageUrl);
        entity.setScheduledAt(when);
        entity.setStatus(ScheduledPostStatus.PENDING);
        entity.setContentType(contentType != null ? contentType : "TEXT");
        entity.setPollOptions(pollOptions);
        entity.setPollAnonymous(pollAnonymous);
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<ScheduledPostEntity> pending(long userId) {
        return repository.findByUserIdAndStatusOrderByScheduledAtAsc(userId, ScheduledPostStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public Optional<ScheduledPostEntity> find(long id) {
        return repository.findById(id);
    }

    @Transactional
    public boolean cancel(long id, long userId) {
        return repository.findById(id)
                .filter(e -> e.getUserId().equals(userId) && e.getStatus() == ScheduledPostStatus.PENDING)
                .map(e -> {
                    e.setStatus(ScheduledPostStatus.CANCELLED);
                    repository.save(e);
                    return true;
                })
                .orElse(false);
    }
}
