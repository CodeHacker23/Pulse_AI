CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    telegram_id         BIGINT       NOT NULL UNIQUE,
    username            VARCHAR(255),
    first_name          VARCHAR(255),
    last_name           VARCHAR(255),
    language_code       VARCHAR(10)  NOT NULL DEFAULT 'ru',
    balance             INT          NOT NULL DEFAULT 0,
    free_analysis_used  BOOLEAN      NOT NULL DEFAULT FALSE,
    total_requests      INT          NOT NULL DEFAULT 0,
    total_ideas_received INT         NOT NULL DEFAULT 0,
    total_posts_published INT        NOT NULL DEFAULT 0,
    active_channel_id   BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_active_at      TIMESTAMPTZ
);

CREATE INDEX idx_users_telegram_id ON users (telegram_id);
CREATE INDEX idx_users_last_active_at ON users (last_active_at);

CREATE TABLE user_settings (
    user_id                 BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    notifications_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    timezone                VARCHAR(64) NOT NULL DEFAULT 'Europe/Moscow'
);

CREATE TABLE channels (
    id                  BIGSERIAL PRIMARY KEY,
    telegram_chat_id    BIGINT       NOT NULL UNIQUE,
    username            VARCHAR(255),
    title               VARCHAR(512) NOT NULL,
    subscriber_count    INT,
    owner_user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    bot_is_admin        BOOLEAN      NOT NULL DEFAULT FALSE,
    can_post_messages   BOOLEAN      NOT NULL DEFAULT FALSE,
    can_view_stats      BOOLEAN      NOT NULL DEFAULT FALSE,
    connection_status   VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    last_sync_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_channels_owner_user_id ON channels (owner_user_id);
CREATE INDEX idx_channels_telegram_chat_id ON channels (telegram_chat_id);

ALTER TABLE users
    ADD CONSTRAINT fk_users_active_channel
        FOREIGN KEY (active_channel_id) REFERENCES channels (id) ON DELETE SET NULL;
