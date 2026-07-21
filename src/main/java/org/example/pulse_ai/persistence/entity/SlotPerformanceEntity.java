package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Накопленная эффективность слота публикации по конкретному каналу.
 * avgRatio = средний (фактические просмотры / средние по каналу) для этого слота.
 * >1 — слот сильнее среднего, <1 — слабее.
 */
@Getter
@Setter
@Entity
@Table(name = "slot_performance",
        uniqueConstraints = @UniqueConstraint(name = "uq_slot_performance", columnNames = {"channel_id", "slot_key"}))
public class SlotPerformanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "slot_key", nullable = false, length = 48)
    private String slotKey;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount = 0;

    @Column(name = "avg_ratio", nullable = false)
    private double avgRatio = 1.0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
