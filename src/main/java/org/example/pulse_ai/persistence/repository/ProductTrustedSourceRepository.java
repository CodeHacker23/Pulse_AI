package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ProductTrustedSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductTrustedSourceRepository extends JpaRepository<ProductTrustedSourceEntity, Long> {

    List<ProductTrustedSourceEntity> findByActiveTrueOrderByTrustLevelDesc();
}
