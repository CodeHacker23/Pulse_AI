package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@Entity
@Table(name = "outreach_monthly_usage")
@IdClass(OutreachMonthlyUsageEntity.Pk.class)
public class OutreachMonthlyUsageEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "month_key", length = 7)
    private String monthKey;

    @Column(name = "sent_count", nullable = false)
    private int sentCount = 0;

    @Getter
    @Setter
    public static class Pk implements Serializable {
        private Long userId;
        private String monthKey;
    }
}
