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
@Table(name = "ad_placements")
public class AdPlacementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "owner_channel_id")
    private Long ownerChannelId;

    @Column(name = "target_username", nullable = false, length = 128)
    private String targetUsername;

    @Column(name = "target_title", length = 256)
    private String targetTitle;

    @Column(name = "scraped_channel_id")
    private Long scrapedChannelId;

    @Column(name = "quality_score")
    private Short qualityScore;

    @Column(name = "quality_verdict", nullable = false, length = 24)
    private String qualityVerdict = "UNKNOWN";

    @Column(name = "quality_notes", columnDefinition = "TEXT")
    private String qualityNotes;

    @Column(name = "posts_last_30d")
    private Integer postsLast30d;

    @Column(name = "ad_ratio_percent")
    private Short adRatioPercent;

    @Column(name = "avg_views")
    private Integer avgViews;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
