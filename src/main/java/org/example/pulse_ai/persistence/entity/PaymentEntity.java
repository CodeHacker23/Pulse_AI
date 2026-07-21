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
import org.example.pulse_ai.domain.payment.PaymentStatus;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "package_id", nullable = false)
    private Short packageId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "amount_rub", nullable = false)
    private int amountRub;

    @Column(name = "discount_percent", nullable = false)
    private short discountPercent;

    @Column(name = "promo_code", length = 64)
    private String promoCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "requests_credited")
    private Integer requestsCredited;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "perks_remaining_to_pick", nullable = false)
    private short perksRemainingToPick;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
