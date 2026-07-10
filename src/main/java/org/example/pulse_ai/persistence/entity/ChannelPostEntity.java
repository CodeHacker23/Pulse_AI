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

@Getter
@Setter
@Entity
@Table(name = "channel_posts")
public class ChannelPostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "telegram_message_id", nullable = false)
    private Integer telegramMessageId;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "text_preview", length = 500)
    private String textPreview;

    @Column(name = "full_text", columnDefinition = "TEXT")
    private String fullText;

    private Integer views;
    private Integer forwards;
    @Column(name = "reactions_total")
    private Integer reactionsTotal;
    @Column(name = "replies_count")
    private Integer repliesCount;
    @Column(name = "engagement_rate", precision = 6, scale = 4)
    private BigDecimal engagementRate;
    @Column(name = "media_type", length = 32)
    private String mediaType;
    @Column(name = "is_forwarded", nullable = false, columnDefinition = "boolean default false")
    private boolean forwarded;
    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @PrePersist
    void onCreate() {
        if (collectedAt == null) {
            collectedAt = Instant.now();
        }
    }
}
