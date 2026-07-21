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
@Table(name = "outreach_prospects")
public class OutreachProspectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(length = 128)
    private String username;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "telegram_user_id")
    private Long telegramUserId;

    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "personalized_text", columnDefinition = "TEXT")
    private String personalizedText;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "scout_account_id")
    private Long scoutAccountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
