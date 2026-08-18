package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.OutreachProspectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutreachProspectRepository extends JpaRepository<OutreachProspectEntity, Long> {

    List<OutreachProspectEntity> findByCampaignIdOrderByCreatedAtAsc(Long campaignId);

    long countByCampaignIdAndStatus(Long campaignId, String status);

    long countByStatus(String status);

    Optional<OutreachProspectEntity> findFirstByCampaignIdAndStatusOrderByCreatedAtAsc(
            Long campaignId, String status);

    List<OutreachProspectEntity> findTop15ByCampaignIdAndStatusOrderByRepliedAtDesc(
            Long campaignId, String status);
}
