package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.example.pulse_ai.domain.channel.ConnectionStatus;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "channels")
public class ChannelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_chat_id", nullable = false, unique = true)
    private Long telegramChatId;

    private String username;

    @Column(nullable = false)
    private String title;

    @Column(name = "subscriber_count")
    private Integer subscriberCount;

    @Column(length = 128)
    private String category;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "bot_is_admin", nullable = false)
    private boolean botIsAdmin;

    @Column(name = "can_post_messages", nullable = false)
    private boolean canPostMessages;

    @Column(name = "can_view_stats", nullable = false)
    private boolean canViewStats;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false)
    private ConnectionStatus connectionStatus = ConnectionStatus.ACTIVE;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    /** Привязанная группа обсуждений (комментарии). Заполняется автоматически при первом авто-форварде поста. */
    @Column(name = "linked_discussion_chat_id")
    private Long linkedDiscussionChatId;

    /** Включён ли мини-агент, который ловит горячие лиды в комментариях. */
    @Column(name = "lead_agent_enabled", nullable = false)
    private boolean leadAgentEnabled = false;

    /** База ответов админа (цены, доставка, оффер) — питает черновики ответов агента. */
    @Column(name = "sales_faq", columnDefinition = "TEXT")
    private String salesFaq;

    /** Книга возражений: «дорого → …», рабочие формулировки админа. */
    @Column(name = "sales_objections", columnDefinition = "TEXT")
    private String salesObjections;

    /** Пользовательский промпт стиля постов — приоритетнее примеров с канала. */
    @Column(name = "content_style_prompt", columnDefinition = "TEXT")
    private String contentStylePrompt;

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
