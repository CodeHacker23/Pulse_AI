package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ContentPlanItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContentPlanItemRepository extends JpaRepository<ContentPlanItemEntity, Long> {

    List<ContentPlanItemEntity> findTop15ByChannelIdAndStatusInOrderByUpdatedAtDesc(
            Long channelId, Collection<String> statuses);

    List<ContentPlanItemEntity> findByChannelIdAndStatusInAndUpdatedAtAfter(
            Long channelId, Collection<String> statuses, Instant after);

    Optional<ContentPlanItemEntity> findFirstByChannelIdAndIdeaId(Long channelId, Long ideaId);

    Optional<ContentPlanItemEntity> findFirstByChannelIdAndTopicKeyOrderByUpdatedAtDesc(
            Long channelId, String topicKey);
}
