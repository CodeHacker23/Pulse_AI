-- P1.1: scout-аккаунты, парсинг групп, follow-up лидов.

CREATE TABLE IF NOT EXISTS scout_accounts (
    id BIGSERIAL PRIMARY KEY,
    label VARCHAR(64) NOT NULL,
    account_type VARCHAR(16) NOT NULL DEFAULT 'OUTREACH',
    external_ref VARCHAR(128),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    daily_limit INT NOT NULL DEFAULT 15,
    sent_today INT NOT NULL DEFAULT 0,
    last_sent_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS group_parse_jobs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    campaign_id BIGINT,
    group_link VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    members_found INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_group_parse_jobs_status ON group_parse_jobs (status, created_at);

ALTER TABLE outreach_prospects ADD COLUMN IF NOT EXISTS scout_account_id BIGINT;

ALTER TABLE hot_leads ADD COLUMN IF NOT EXISTS follow_up_sent BOOLEAN NOT NULL DEFAULT FALSE;
