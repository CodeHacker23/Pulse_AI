package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Горячий лид, пойманный агентом в комментариях под постом канала. */
@Getter
@Setter
@Entity
@Table(name = "hot_leads",
        uniqueConstraints = @UniqueConstraint(name = "uq_hot_lead",
                columnNames = {"discussion_chat_id", "comment_message_id"}))
public class HotLeadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "discussion_chat_id", nullable = false)
    private Long discussionChatId;

    @Column(name = "comment_message_id", nullable = false)
    private Long commentMessageId;

    @Column(name = "commenter_user_id")
    private Long commenterUserId;

    @Column(name = "commenter_username", length = 128)
    private String commenterUsername;

    @Column(name = "commenter_name", length = 256)
    private String commenterName;

    @Column(name = "comment_text", columnDefinition = "TEXT")
    private String commentText;

    @Column(name = "category", length = 32)
    private String category;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "comment_link", length = 512)
    private String commentLink;

    @Column(name = "notified", nullable = false)
    private boolean notified = false;

    /** CRM-статус: NEW / IN_PROGRESS / WON / LOST. */
    @Column(name = "status", length = 16, nullable = false)
    private String status = "NEW";

    /** Черновик ответа, сгенерированный агентом (для отправки в один клик). */
    @Column(name = "suggested_reply", columnDefinition = "TEXT")
    private String suggestedReply;

    @Column(name = "follow_up_sent", nullable = false)
    private boolean followUpSent = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
