package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ScoutTargetChatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScoutTargetChatRepository extends JpaRepository<ScoutTargetChatEntity, Long> {

    Optional<ScoutTargetChatEntity> findByNormalizedLink(String normalizedLink);

    List<ScoutTargetChatEntity> findByStatusOrderByPriorityDescIdAsc(String status);

    long countByStatus(String status);
}
