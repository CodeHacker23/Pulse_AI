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
@Table(name = "product_trusted_sources")
public class ProductTrustedSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(name = "source_type", nullable = false, length = 16)
    private String sourceType;

    @Column(name = "url_or_username", nullable = false, length = 512)
    private String urlOrUsername;

    @Column(name = "trust_level", nullable = false)
    private short trustLevel = 5;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
