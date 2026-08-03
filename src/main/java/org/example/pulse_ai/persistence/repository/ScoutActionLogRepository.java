package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ScoutActionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoutActionLogRepository extends JpaRepository<ScoutActionLogEntity, Long> {

    List<ScoutActionLogEntity> findTop50ByOrderByCreatedAtDesc();

    List<ScoutActionLogEntity> findTop30ByScoutAccountIdOrderByCreatedAtDesc(Long scoutAccountId);
}
