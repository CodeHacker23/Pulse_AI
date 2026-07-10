package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "analysis_snapshots")
public class AnalysisSnapshotEntity {

    @Id
    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "avg_views")
    private Integer avgViews;

    @Column(name = "avg_engagement_rate", precision = 6, scale = 4)
    private BigDecimal avgEngagementRate;

    @Column(name = "views_delta_percent", precision = 6, scale = 2)
    private BigDecimal viewsDeltaPercent;

    @Column(name = "post_count")
    private Integer postCount;

    @Lob
    @Column(name = "best_publish_slots", columnDefinition = "TEXT")
    private String bestPublishSlotsJson;

    @Lob
    @Column(name = "avoid_slots", columnDefinition = "TEXT")
    private String avoidSlotsJson;

    @Lob
    @Column(name = "top_posts", columnDefinition = "TEXT")
    private String topPostsJson;

    @Lob
    @Column(name = "worst_posts", columnDefinition = "TEXT")
    private String worstPostsJson;

    @Lob
    @Column(name = "working_topics", columnDefinition = "TEXT")
    private String workingTopicsJson;

    @Lob
    @Column(name = "daily_views", columnDefinition = "TEXT")
    private String dailyViewsJson;

    @Column(name = "frequency_recommendation")
    private String frequencyRecommendation;

    @Lob
    @Column(name = "raw_metrics", columnDefinition = "TEXT")
    private String rawMetricsJson;

    @Lob
    @Column(name = "deep_analysis_sections", columnDefinition = "TEXT")
    private String deepAnalysisSectionsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
