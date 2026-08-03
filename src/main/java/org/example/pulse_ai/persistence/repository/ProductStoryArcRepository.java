package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ProductStoryArcEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductStoryArcRepository extends JpaRepository<ProductStoryArcEntity, Long> {

    List<ProductStoryArcEntity> findTop5ByOrderByCreatedAtDesc();

    Optional<ProductStoryArcEntity> findFirstByStatusOrderByCreatedAtDesc(String status);
}
