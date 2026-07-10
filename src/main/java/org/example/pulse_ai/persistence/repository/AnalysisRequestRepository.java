package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.domain.request.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AnalysisRequestRepository extends JpaRepository<AnalysisRequestEntity, Long> {

    List<AnalysisRequestEntity> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<RequestStatus> statuses);

    List<AnalysisRequestEntity> findByUserIdAndStatusIn(Long userId, Collection<RequestStatus> statuses);
}
