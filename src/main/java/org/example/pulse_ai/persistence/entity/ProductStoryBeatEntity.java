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
@Table(name = "product_story_beats")
public class ProductStoryBeatEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "arc_id", nullable = false)
    private Long arcId;

    @Column(name = "beat_index", nullable = false)
    private short beatIndex;

    @Column(name = "beat_key", nullable = false, length = 32)
    private String beatKey;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String outline;

    @Column(name = "draft_text", columnDefinition = "TEXT")
    private String draftText;

    /** PLANNED / READY / SCHEDULED / PUBLISHED / SKIPPED */
    @Column(nullable = false, length = 16)
    private String status = "PLANNED";

    @Column(name = "channel_post_id")
    private Long channelPostId;

    @Column(name = "telegram_message_id")
    private Integer telegramMessageId;

    @Column(name = "post_link", length = 512)
    private String postLink;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
