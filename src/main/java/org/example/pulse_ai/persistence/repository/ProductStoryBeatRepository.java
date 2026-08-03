package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ProductStoryBeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProductStoryBeatRepository extends JpaRepository<ProductStoryBeatEntity, Long> {

    List<ProductStoryBeatEntity> findByArcIdOrderByBeatIndexAsc(Long arcId);

    Optional<ProductStoryBeatEntity> findFirstByArcIdAndStatusInOrderByBeatIndexAsc(Long arcId, List<String> statuses);

    List<ProductStoryBeatEntity> findByStatusAndScheduledForLessThanEqualOrderByScheduledForAsc(
            String status, Instant when);
}
