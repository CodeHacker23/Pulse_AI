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
@Table(name = "product_releases")
public class ProductReleaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String version;

    @Column(nullable = false, length = 256)
    private String title;

    /** Буллеты с ▪️, по одному на строку */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String bullets;

    /** FEATURE / FIX / TEST / INSIGHT / UPDATE */
    @Column(nullable = false, length = 16)
    private String category = "UPDATE";

    /** DRAFT / READY / POSTED */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(name = "released_at", nullable = false)
    private Instant releasedAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "channel_post_id")
    private Long channelPostId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (releasedAt == null) {
            releasedAt = Instant.now();
        }
    }
}
