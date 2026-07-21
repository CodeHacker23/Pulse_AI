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
@Table(name = "user_entitlements")
public class UserEntitlementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "perk_code", nullable = false, length = 32)
    private String perkCode;

    @Column(name = "uses_remaining")
    private Integer usesRemaining;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "source_payment_id")
    private Long sourcePaymentId;

    @PrePersist
    void onCreate() {
        if (grantedAt == null) {
            grantedAt = Instant.now();
        }
    }
}
