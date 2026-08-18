-- Ops-бэкап ЛС своих скаутов (текст + edit/delete). Не релей-бот.
-- Медиа — отдельным шагом.

CREATE TABLE IF NOT EXISTS scout_message_archive (
    id BIGSERIAL PRIMARY KEY,
    scout_account_id BIGINT NOT NULL,
    peer_id VARCHAR(64) NOT NULL,
    peer_username VARCHAR(64),
    peer_name VARCHAR(256),
    tg_message_id BIGINT NOT NULL,
    direction VARCHAR(8) NOT NULL DEFAULT 'IN',
    body TEXT,
    edited BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    message_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_scout_msg_archive UNIQUE (scout_account_id, peer_id, tg_message_id)
);

CREATE INDEX IF NOT EXISTS idx_scout_msg_archive_account_time
    ON scout_message_archive (scout_account_id, message_at DESC);

CREATE INDEX IF NOT EXISTS idx_scout_msg_archive_peer
    ON scout_message_archive (scout_account_id, peer_id, message_at);
