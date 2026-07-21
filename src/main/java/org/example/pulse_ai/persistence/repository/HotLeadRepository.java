package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.HotLeadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface HotLeadRepository extends JpaRepository<HotLeadEntity, Long> {

    boolean existsByDiscussionChatIdAndCommentMessageId(Long discussionChatId, Long commentMessageId);

    Optional<HotLeadEntity> findByDiscussionChatIdAndCommentMessageId(Long discussionChatId, Long commentMessageId);

    List<HotLeadEntity> findTop10ByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    long countByOwnerUserId(Long ownerUserId);

    long countByOwnerUserIdAndStatus(Long ownerUserId, String status);

    List<HotLeadEntity> findByStatusInAndFollowUpSentFalseAndCreatedAtBefore(
            List<String> statuses, Instant createdAtBefore);
}
