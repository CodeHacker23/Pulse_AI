-- Content plan memory per channel (Wave 1).

CREATE TABLE IF NOT EXISTS content_plan_items (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    title VARCHAR(512) NOT NULL,
    topic_key VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUGGESTED',
    source_request_id BIGINT,
    idea_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_content_plan_channel_status
    ON content_plan_items (channel_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_content_plan_channel_topic
    ON content_plan_items (channel_id, topic_key);
