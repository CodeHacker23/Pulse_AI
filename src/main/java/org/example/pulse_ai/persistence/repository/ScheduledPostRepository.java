package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.domain.schedule.ScheduledPostStatus;
import org.example.pulse_ai.persistence.entity.ScheduledPostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ScheduledPostRepository extends JpaRepository<ScheduledPostEntity, Long> {

    List<ScheduledPostEntity> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            ScheduledPostStatus status, Instant threshold);

    List<ScheduledPostEntity> findByUserIdAndStatusOrderByScheduledAtAsc(
            Long userId, ScheduledPostStatus status);
}
