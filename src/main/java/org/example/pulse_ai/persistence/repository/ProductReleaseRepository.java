package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ProductReleaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductReleaseRepository extends JpaRepository<ProductReleaseEntity, Long> {

    Optional<ProductReleaseEntity> findByVersion(String version);

    List<ProductReleaseEntity> findByStatusOrderByReleasedAtDesc(String status);

    List<ProductReleaseEntity> findTop20ByOrderByReleasedAtDesc();

    List<ProductReleaseEntity> findByStatusInOrderByReleasedAtDesc(List<String> statuses);
}
