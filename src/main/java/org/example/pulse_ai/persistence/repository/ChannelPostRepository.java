package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChannelPostRepository extends JpaRepository<ChannelPostEntity, Long> {

    Optional<ChannelPostEntity> findByChannelIdAndTelegramMessageId(Long channelId, Integer telegramMessageId);

    List<ChannelPostEntity> findByChannelIdAndPublishedAtBetweenOrderByPublishedAtAsc(
            Long channelId,
            Instant from,
            Instant to
    );

    int countByChannelId(Long channelId);

    List<ChannelPostEntity> findByChannelIdOrderByPublishedAtAsc(Long channelId);
}
