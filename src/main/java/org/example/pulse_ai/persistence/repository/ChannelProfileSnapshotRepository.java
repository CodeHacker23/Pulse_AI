package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ChannelProfileSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelProfileSnapshotRepository extends JpaRepository<ChannelProfileSnapshotEntity, Long> {

    Optional<ChannelProfileSnapshotEntity> findByRequestId(Long requestId);

    List<ChannelProfileSnapshotEntity> findByChannelIdOrderByAnalyzedAtDesc(Long channelId);

    List<ChannelProfileSnapshotEntity> findByCategoryOrderByAnalyzedAtDesc(String category);
}
