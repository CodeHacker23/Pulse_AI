package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.SlotPerformanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SlotPerformanceRepository extends JpaRepository<SlotPerformanceEntity, Long> {

    Optional<SlotPerformanceEntity> findByChannelIdAndSlotKey(Long channelId, String slotKey);

    List<SlotPerformanceEntity> findByChannelId(Long channelId);
}
