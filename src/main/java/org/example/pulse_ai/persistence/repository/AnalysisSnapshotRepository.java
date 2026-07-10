package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.AnalysisSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisSnapshotRepository extends JpaRepository<AnalysisSnapshotEntity, Long> {
}
