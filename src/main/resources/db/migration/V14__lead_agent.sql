-- Мини-агент в комментариях: привязка группы обсуждений и переключатель.
ALTER TABLE channels ADD COLUMN IF NOT EXISTS linked_discussion_chat_id BIGINT;
ALTER TABLE channels ADD COLUMN IF NOT EXISTS lead_agent_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Горячие лиды, пойманные в комментариях под постами.
CREATE TABLE IF NOT EXISTS hot_leads (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    discussion_chat_id BIGINT NOT NULL,
    comment_message_id BIGINT NOT NULL,
    commenter_user_id BIGINT,
    commenter_username VARCHAR(128),
    commenter_name VARCHAR(256),
    comment_text TEXT,
    category VARCHAR(32),
    reason VARCHAR(512),
    comment_link VARCHAR(512),
    notified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_hot_lead UNIQUE (discussion_chat_id, comment_message_id)
);

CREATE INDEX IF NOT EXISTS idx_hot_leads_owner ON hot_leads (owner_user_id, created_at);
