CREATE TABLE channel_posts (
    id                  BIGSERIAL PRIMARY KEY,
    channel_id          BIGINT       NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    telegram_message_id INT          NOT NULL,
    published_at        TIMESTAMPTZ  NOT NULL,
    text_preview        VARCHAR(500),
    full_text           TEXT,
    views               INT,
    forwards            INT,
    reactions_total     INT,
    replies_count       INT,
    engagement_rate     DECIMAL(6, 4),
    media_type          VARCHAR(32),
    collected_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (channel_id, telegram_message_id)
);

CREATE INDEX idx_channel_posts_channel_published ON channel_posts (channel_id, published_at DESC);
CREATE INDEX idx_channel_posts_channel_views ON channel_posts (channel_id, views DESC);

CREATE TABLE analysis_requests (
    id                          BIGSERIAL PRIMARY KEY,
    user_id                     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel_id                  BIGINT       NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    type                        VARCHAR(16)  NOT NULL,
    status                      VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    period_from                 DATE         NOT NULL,
    period_to                   DATE         NOT NULL,
    progress_percent            SMALLINT     NOT NULL DEFAULT 0,
    progress_stage              VARCHAR(64),
    balance_charged             BOOLEAN      NOT NULL DEFAULT FALSE,
    error_message               TEXT,
    telegram_status_message_id  INT,
    started_at                  TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_analysis_requests_user_created ON analysis_requests (user_id, created_at DESC);
CREATE INDEX idx_analysis_requests_status ON analysis_requests (status);
CREATE INDEX idx_analysis_requests_channel ON analysis_requests (channel_id);
