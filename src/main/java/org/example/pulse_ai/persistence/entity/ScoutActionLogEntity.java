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
@Table(name = "scout_action_log")
public class ScoutActionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scout_account_id")
    private Long scoutAccountId;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 24)
    private String action;

    @Column(nullable = false, length = 16)
    private String status = "OK";

    @Column(length = 1024)
    private String payload;

    @Column(name = "error_text", length = 512)
    private String errorText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
