-- Персональный менеджер канала: учёт бесплатных вопросов (триал).
CREATE TABLE IF NOT EXISTS manager_usage (
    user_id BIGINT PRIMARY KEY,
    questions_used INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Обучение на охватах: статистика фактической эффективности слотов публикации.
CREATE TABLE IF NOT EXISTS slot_performance (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    slot_key VARCHAR(48) NOT NULL,
    sample_count INT NOT NULL DEFAULT 0,
    avg_ratio DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_slot_performance UNIQUE (channel_id, slot_key)
);

-- Фактические просмотры опубликованных постов (дочитываем через сутки).
ALTER TABLE published_posts ADD COLUMN IF NOT EXISTS perf_measured BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE published_posts ADD COLUMN IF NOT EXISTS perf_views INTEGER;
ALTER TABLE published_posts ADD COLUMN IF NOT EXISTS perf_measured_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_published_posts_perf
    ON published_posts (perf_measured, published_at);
