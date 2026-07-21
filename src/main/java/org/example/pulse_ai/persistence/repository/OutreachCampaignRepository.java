package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutreachCampaignRepository extends JpaRepository<OutreachCampaignEntity, Long> {

    List<OutreachCampaignEntity> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndStatusIn(Long userId, List<String> statuses);

    Optional<OutreachCampaignEntity> findByIdAndUserId(Long id, Long userId);

    List<OutreachCampaignEntity> findByStatus(String status);
}
