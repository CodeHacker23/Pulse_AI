package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.OutreachMonthlyUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutreachMonthlyUsageRepository
        extends JpaRepository<OutreachMonthlyUsageEntity, OutreachMonthlyUsageEntity.Pk> {
}
