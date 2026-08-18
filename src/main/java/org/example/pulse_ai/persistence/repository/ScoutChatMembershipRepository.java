package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ScoutChatMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScoutChatMembershipRepository extends JpaRepository<ScoutChatMembershipEntity, Long> {

    Optional<ScoutChatMembershipEntity> findByChatIdAndScoutAccountId(Long chatId, Long scoutAccountId);

    long countByStatus(String status);

    long countByScoutAccountIdAndStatus(Long scoutAccountId, String status);

    List<ScoutChatMembershipEntity> findByScoutAccountId(Long scoutAccountId);

    List<ScoutChatMembershipEntity> findByChatId(Long chatId);

    long countByScoutAccountIdAndStatusAndJoinedAtGreaterThanEqual(
            Long scoutAccountId, String status, Instant joinedAt);

    @Query("""
            SELECT m FROM ScoutChatMembershipEntity m
            WHERE m.status IN ('PENDING', 'FAILED')
              AND (m.nextAttemptAt IS NULL OR m.nextAttemptAt <= :now)
              AND m.attempts < :maxAttempts
            ORDER BY CASE WHEN m.status = 'PENDING' THEN 0 ELSE 1 END, m.id ASC
            """)
    List<ScoutChatMembershipEntity> findReadyQueue(@Param("now") Instant now,
                                                   @Param("maxAttempts") int maxAttempts);

    List<ScoutChatMembershipEntity> findTop30ByOrderByIdDesc();
}
