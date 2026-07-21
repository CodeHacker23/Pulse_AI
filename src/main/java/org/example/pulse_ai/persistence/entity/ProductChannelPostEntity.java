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
import org.example.pulse_ai.domain.product.ProductChannelPostStatus;
import org.example.pulse_ai.domain.product.ProductPostRubric;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "product_channel_posts")
public class ProductChannelPostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProductPostRubric rubric;

    @Column(name = "draft_text", nullable = false, columnDefinition = "TEXT")
    private String draftText;

    @Column(name = "final_text", columnDefinition = "TEXT")
    private String finalText;

    @Column(name = "telegram_message_id")
    private Integer telegramMessageId;

    @Column(name = "post_link", length = 512)
    private String postLink;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProductChannelPostStatus status = ProductChannelPostStatus.DRAFT;

    @Column(name = "created_by_telegram_id", nullable = false)
    private Long createdByTelegramId;

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
