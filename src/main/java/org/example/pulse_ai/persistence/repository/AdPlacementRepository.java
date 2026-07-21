package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.AdPlacementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdPlacementRepository extends JpaRepository<AdPlacementEntity, Long> {

    List<AdPlacementEntity> findTop15ByUserIdOrderByLastCheckedAtDescCreatedAtDesc(Long userId);

    Optional<AdPlacementEntity> findByIdAndUserId(Long id, Long userId);
}
