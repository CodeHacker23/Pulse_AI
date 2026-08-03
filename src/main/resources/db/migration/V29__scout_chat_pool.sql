-- Общий пул групп/каналов для PARSER/OBSERVER.
-- Список живёт отдельно от аккаунта: сгорел скаут → новый берёт тот же пул.

CREATE TABLE IF NOT EXISTS scout_target_chats (
    id BIGSERIAL PRIMARY KEY,
    link VARCHAR(512) NOT NULL,
    normalized_link VARCHAR(512) NOT NULL,
    title VARCHAR(256),
    kind VARCHAR(16) NOT NULL DEFAULT 'GROUP',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    priority INT NOT NULL DEFAULT 100,
    notes VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_scout_target_chats_norm
    ON scout_target_chats (normalized_link);
CREATE INDEX IF NOT EXISTS idx_scout_target_chats_status
    ON scout_target_chats (status, priority DESC, id);

CREATE TABLE IF NOT EXISTS scout_chat_memberships (
    id BIGSERIAL PRIMARY KEY,
    chat_id BIGINT NOT NULL REFERENCES scout_target_chats (id) ON DELETE CASCADE,
    scout_account_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    joined_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_scout_chat_membership UNIQUE (chat_id, scout_account_id)
);

CREATE INDEX IF NOT EXISTS idx_scout_chat_memberships_queue
    ON scout_chat_memberships (status, next_attempt_at NULLS FIRST, id);
CREATE INDEX IF NOT EXISTS idx_scout_chat_memberships_account
    ON scout_chat_memberships (scout_account_id, status);

-- Лимит SpamBot на аккаунт (для SENDER/OUTREACH).
ALTER TABLE scout_accounts
    ADD COLUMN IF NOT EXISTS spambot_today INT NOT NULL DEFAULT 0;
ALTER TABLE scout_accounts
    ADD COLUMN IF NOT EXISTS last_spambot_at TIMESTAMP WITH TIME ZONE;
