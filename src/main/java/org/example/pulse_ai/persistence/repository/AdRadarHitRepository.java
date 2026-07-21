package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.AdRadarHitEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdRadarHitRepository extends JpaRepository<AdRadarHitEntity, Long> {

    List<AdRadarHitEntity> findTop10ByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
}
