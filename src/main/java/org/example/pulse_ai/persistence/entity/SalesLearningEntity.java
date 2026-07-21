package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Читаемый вывод агента-продавца после закрытия лида (won/lost) — для обучения и обзора админом. */
@Getter
@Setter
@Entity
@Table(name = "sales_learnings")
public class SalesLearningEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
