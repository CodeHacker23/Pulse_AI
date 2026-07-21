package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.PublishedPostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PublishedPostRepository extends JpaRepository<PublishedPostEntity, Long> {

    List<PublishedPostEntity> findByPerfMeasuredFalseAndPublishedAtBetween(Instant from, Instant to);
}
