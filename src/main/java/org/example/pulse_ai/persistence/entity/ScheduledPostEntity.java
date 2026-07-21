package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.example.pulse_ai.domain.schedule.ScheduledPostStatus;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "scheduled_posts")
public class ScheduledPostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "generated_post_id", nullable = false)
    private Long generatedPostId;

    @Column(name = "final_text", nullable = false, columnDefinition = "TEXT")
    private String finalText;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    @Column(name = "content_type", length = 16, nullable = false)
    private String contentType = "TEXT";

    @Column(name = "poll_options", columnDefinition = "TEXT")
    private String pollOptions;

    @Column(name = "poll_anonymous", nullable = false)
    private boolean pollAnonymous = false;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ScheduledPostStatus status = ScheduledPostStatus.PENDING;

    @Column(name = "published_message_id")
    private Integer publishedMessageId;

    @Column(name = "post_link", length = 512)
    private String postLink;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = ScheduledPostStatus.PENDING;
        }
    }
}
