package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.PackageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PackageRepository extends JpaRepository<PackageEntity, Short> {

    List<PackageEntity> findByActiveTrueOrderBySortOrderAsc();
}
