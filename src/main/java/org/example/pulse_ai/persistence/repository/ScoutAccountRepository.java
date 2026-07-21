package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScoutAccountRepository extends JpaRepository<ScoutAccountEntity, Long> {

    List<ScoutAccountEntity> findByAccountTypeAndStatusOrderByLastSentAtAscIdAsc(
            String accountType, String status);

    Optional<ScoutAccountEntity> findByLabel(String label);
}
