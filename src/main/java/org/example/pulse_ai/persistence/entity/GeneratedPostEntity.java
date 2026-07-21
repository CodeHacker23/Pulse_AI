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
@Table(name = "generated_posts")
public class GeneratedPostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "idea_id", nullable = false)
    private Long ideaId;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "variant_a", nullable = false, columnDefinition = "TEXT")
    private String variantA;

    @Column(name = "variant_b", columnDefinition = "TEXT")
    private String variantB;

    @Column(name = "variant_c", columnDefinition = "TEXT")
    private String variantC;

    @Column(length = 512)
    private String cta;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    /** TEXT или POLL — нативный опрос Telegram. */
    @Column(name = "content_type", length = 16, nullable = false)
    private String contentType = "TEXT";

    /** JSON-массив вариантов ответа для POLL, напр. ["Да","Нет"]. */
    @Column(name = "poll_options", columnDefinition = "TEXT")
    private String pollOptions;

    /**
     * true = анонимный опрос (не видно, кто голосовал).
     * false = неанонимный — видно голоса пользователей (дефолт для контент-каналов).
     */
    @Column(name = "poll_anonymous", nullable = false)
    private boolean pollAnonymous = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
