package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ProductStyleSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductStyleSnapshotEntityRepository extends JpaRepository<ProductStyleSnapshotEntity, Long> {

    Optional<ProductStyleSnapshotEntity> findFirstByOrderByCreatedAtDesc();
}
