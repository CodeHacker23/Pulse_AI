-- Jarvis P1.5: Ad Radar — мониторинг чатов и скоринг площадок.

CREATE TABLE IF NOT EXISTS ad_watch_sources (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    owner_channel_id BIGINT,
    source_type VARCHAR(16) NOT NULL DEFAULT 'CHAT',
    link_or_username VARCHAR(256) NOT NULL,
    title VARCHAR(256),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ad_watch_user ON ad_watch_sources (user_id, active);

CREATE TABLE IF NOT EXISTS ad_placements (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    owner_channel_id BIGINT,
    target_username VARCHAR(128) NOT NULL,
    target_title VARCHAR(256),
    scraped_channel_id BIGINT,
    quality_score SMALLINT,
    quality_verdict VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    quality_notes TEXT,
    posts_last_30d INT,
    ad_ratio_percent SMALLINT,
    avg_views INT,
    last_checked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ad_placements_user ON ad_placements (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ad_radar_hits (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    watch_source_id BIGINT,
    hit_type VARCHAR(24) NOT NULL DEFAULT 'AD_SIGNAL',
    snippet TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ad_radar_hits_user ON ad_radar_hits (user_id, status, created_at DESC);
