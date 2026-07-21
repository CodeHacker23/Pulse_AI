-- Отложенная публикация постов (планировщик).
CREATE TABLE IF NOT EXISTS scheduled_posts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    generated_post_id BIGINT NOT NULL,
    final_text TEXT NOT NULL,
    image_url VARCHAR(1024),
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    published_message_id INTEGER,
    post_link VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scheduled_posts_due
    ON scheduled_posts (status, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_scheduled_posts_user
    ON scheduled_posts (user_id, status);
