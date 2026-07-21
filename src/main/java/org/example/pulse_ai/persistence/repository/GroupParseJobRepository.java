package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.GroupParseJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupParseJobRepository extends JpaRepository<GroupParseJobEntity, Long> {

    List<GroupParseJobEntity> findTop5ByStatusOrderByCreatedAtAsc(String status);

    Optional<GroupParseJobEntity> findByIdAndUserId(Long id, Long userId);
}
