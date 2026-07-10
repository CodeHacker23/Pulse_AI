ALTER TABLE channels
    ADD COLUMN IF NOT EXISTS category VARCHAR(128);

ALTER TABLE analysis_snapshots
    ADD COLUMN IF NOT EXISTS deep_analysis_sections TEXT;

CREATE TABLE IF NOT EXISTS channel_profile_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    channel_id          BIGINT       NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    request_id          BIGINT       NOT NULL UNIQUE,
    username            VARCHAR(255),
    title               VARCHAR(512),
    category            VARCHAR(128),
    subscriber_count    INT,
    post_count          INT,
    avg_views           INT,
    reach_percent       DECIMAL(6, 2),
    err_percent         DECIMAL(6, 2),
    avg_reach           INT,
    citation_index      DECIMAL(10, 2),
    period_from         DATE,
    period_to           DATE,
    analyzed_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_channel_profile_channel ON channel_profile_snapshots (channel_id, analyzed_at DESC);
CREATE INDEX IF NOT EXISTS idx_channel_profile_category ON channel_profile_snapshots (category);
