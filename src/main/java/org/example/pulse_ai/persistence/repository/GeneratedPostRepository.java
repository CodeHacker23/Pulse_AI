package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.GeneratedPostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GeneratedPostRepository extends JpaRepository<GeneratedPostEntity, Long> {

    Optional<GeneratedPostEntity> findByRequestIdAndIdeaId(Long requestId, Long ideaId);
}
