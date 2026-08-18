package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "scout_message_archive")
public class ScoutMessageArchiveEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scout_account_id", nullable = false)
    private Long scoutAccountId;

    @Column(name = "peer_id", nullable = false, length = 64)
    private String peerId;

    @Column(name = "peer_username", length = 64)
    private String peerUsername;

    @Column(name = "peer_name", length = 256)
    private String peerName;

    @Column(name = "tg_message_id", nullable = false)
    private Long tgMessageId;

    /** IN | OUT */
    @Column(nullable = false, length = 8)
    private String direction = "IN";

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private boolean edited = false;

    @Column(nullable = false)
    private boolean deleted = false;

    /** photo | video | voice | audio | document | sticker | other */
    @Column(name = "media_kind", length = 16)
    private String mediaKind;

    /** Относительный путь под data/scout_media/ */
    @Column(name = "media_path", length = 512)
    private String mediaPath;

    @Column(name = "media_mime", length = 128)
    private String mediaMime;

    @Column(name = "media_file_name", length = 256)
    private String mediaFileName;

    @Column(name = "media_size")
    private Long mediaSize;

    @Column(name = "message_at", nullable = false)
    private Instant messageAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (messageAt == null) {
            messageAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
