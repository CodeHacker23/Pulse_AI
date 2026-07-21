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

@Getter
@Setter
@Entity
@Table(name = "ad_watch_sources")
public class AdWatchSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "owner_channel_id")
    private Long ownerChannelId;

    @Column(name = "source_type", nullable = false, length = 16)
    private String sourceType = "CHAT";

    @Column(name = "link_or_username", nullable = false, length = 256)
    private String linkOrUsername;

    @Column(length = 256)
    private String title;

    @Column(nullable = false)
    private boolean active = true;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
