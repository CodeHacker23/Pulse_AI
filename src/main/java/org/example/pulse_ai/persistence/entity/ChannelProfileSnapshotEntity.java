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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "channel_profile_snapshots")
public class ChannelProfileSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "request_id", nullable = false, unique = true)
    private Long requestId;

    private String username;

    @Column(length = 512)
    private String title;

    @Column(length = 128)
    private String category;

    @Column(name = "subscriber_count")
    private Integer subscriberCount;

    @Column(name = "post_count")
    private Integer postCount;

    @Column(name = "avg_views")
    private Integer avgViews;

    @Column(name = "reach_percent", precision = 6, scale = 2)
    private BigDecimal reachPercent;

    @Column(name = "err_percent", precision = 6, scale = 2)
    private BigDecimal errPercent;

    @Column(name = "avg_reach")
    private Integer avgReach;

    @Column(name = "citation_index", precision = 10, scale = 2)
    private BigDecimal citationIndex;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @PrePersist
    void onCreate() {
        if (analyzedAt == null) {
            analyzedAt = Instant.now();
        }
    }
}
