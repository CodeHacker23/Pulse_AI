package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.SalesLearningEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalesLearningRepository extends JpaRepository<SalesLearningEntity, Long> {

    List<SalesLearningEntity> findTop10ByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    List<SalesLearningEntity> findTop5ByChannelIdOrderByCreatedAtDesc(Long channelId);
}
