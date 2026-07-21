package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentIdeaRepository extends JpaRepository<ContentIdeaEntity, Long> {

    List<ContentIdeaEntity> findByRequestIdOrderBySortOrderAsc(Long requestId);

    void deleteByRequestId(Long requestId);
}
