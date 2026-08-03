package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.UserSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingsRepository extends JpaRepository<UserSettingsEntity, Long> {
}
