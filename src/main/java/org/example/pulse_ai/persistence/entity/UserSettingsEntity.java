package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_settings")
public class UserSettingsEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "notifications_enabled", nullable = false)
    private boolean notificationsEnabled = true;

    @Column(nullable = false, length = 64)
    private String timezone = "Europe/Moscow";
}
