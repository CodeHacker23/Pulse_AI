package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "ad_deals")
public class AdDealEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "owner_channel_id")
    private Long ownerChannelId;

    @Column(name = "placement_id")
    private Long placementId;

    @Column(name = "target_username", nullable = false, length = 128)
    private String targetUsername;

    /** INTEREST → BRIEF → AWAITING_ADMIN → AGREED → PAID → LIVE → DONE / REJECTED */
    @Column(nullable = false, length = 24)
    private String status = "INTEREST";

    @Column(name = "pin_format", length = 64)
    private String pinFormat;

    @Column(name = "price_admin_rub")
    private Integer priceAdminRub;

    @Column(name = "price_client_rub")
    private Integer priceClientRub;

    @Column(name = "commission_percent", nullable = false)
    private short commissionPercent = 20;

    @Column(name = "creative_draft", columnDefinition = "TEXT")
    private String creativeDraft;

    @Column(name = "admin_notes", columnDefinition = "TEXT")
    private String adminNotes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
