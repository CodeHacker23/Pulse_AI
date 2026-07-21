package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.AdWatchSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdWatchSourceRepository extends JpaRepository<AdWatchSourceEntity, Long> {

    List<AdWatchSourceEntity> findByUserIdAndActiveTrueOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndActiveTrue(Long userId);

    List<AdWatchSourceEntity> findByActiveTrue();
}
