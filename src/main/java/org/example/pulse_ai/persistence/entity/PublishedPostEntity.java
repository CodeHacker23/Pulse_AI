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
@Table(name = "published_posts")
public class PublishedPostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "generated_post_id", nullable = false)
    private Long generatedPostId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "variant_used", nullable = false, length = 1)
    private char variantUsed = 'A';

    @Column(name = "final_text", nullable = false, columnDefinition = "TEXT")
    private String finalText;

    @Column(name = "telegram_message_id")
    private Integer telegramMessageId;

    @Column(name = "post_link", length = 512)
    private String postLink;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "perf_measured", nullable = false)
    private boolean perfMeasured = false;

    @Column(name = "perf_views")
    private Integer perfViews;

    @Column(name = "perf_measured_at")
    private Instant perfMeasuredAt;

    @PrePersist
    void onCreate() {
        if (publishedAt == null) {
            publishedAt = Instant.now();
        }
    }
}
