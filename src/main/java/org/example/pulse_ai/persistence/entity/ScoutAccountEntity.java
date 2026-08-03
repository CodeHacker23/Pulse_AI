package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "scout_accounts")
public class ScoutAccountEntity {

    /** Ручной ID: watch 1–99, send 100–999 (см. ScoutAccountService). */
    @Id
    @Column(nullable = false)
    private Long id;

    @Column(nullable = false, length = 64)
    private String label;

    @Column(name = "account_type", nullable = false, length = 16)
    private String accountType = "OUTREACH";

    @Column(name = "external_ref", length = 128)
    private String externalRef;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "daily_limit", nullable = false)
    private int dailyLimit = 15;

    @Column(name = "sent_today", nullable = false)
    private int sentToday = 0;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    /** Сколько раз сегодня писали /start @SpamBot (только SENDER/OUTREACH). */
    @Column(name = "spambot_today", nullable = false)
    private int spambotToday = 0;

    @Column(name = "last_spambot_at")
    private Instant lastSpambotAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
