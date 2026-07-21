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
@Table(name = "outreach_campaigns")
public class OutreachCampaignEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "owner_channel_id")
    private Long ownerChannelId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 16)
    private String scenario = "INVITE";

    @Column(name = "source_ref", length = 512)
    private String sourceRef;

    @Column(name = "message_template", nullable = false, columnDefinition = "TEXT")
    private String messageTemplate;

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(name = "daily_limit", nullable = false)
    private int dailyLimit = 15;

    @Column(name = "sent_count", nullable = false)
    private int sentCount = 0;

    @Column(name = "reply_count", nullable = false)
    private int replyCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
