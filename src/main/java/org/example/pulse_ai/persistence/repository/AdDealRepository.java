package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.AdDealEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdDealRepository extends JpaRepository<AdDealEntity, Long> {

    List<AdDealEntity> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<AdDealEntity> findByIdAndUserId(Long id, Long userId);
}
