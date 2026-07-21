-- Jarvis P1: исходящие рассылки в ЛС (кампании + очередь).

CREATE TABLE IF NOT EXISTS outreach_campaigns (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    owner_channel_id BIGINT,
    name VARCHAR(128) NOT NULL,
    scenario VARCHAR(16) NOT NULL DEFAULT 'INVITE',
    source_ref VARCHAR(512),
    message_template TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    daily_limit INT NOT NULL DEFAULT 15,
    sent_count INT NOT NULL DEFAULT 0,
    reply_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outreach_campaigns_user ON outreach_campaigns (user_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS outreach_prospects (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES outreach_campaigns(id) ON DELETE CASCADE,
    username VARCHAR(128),
    display_name VARCHAR(256),
    telegram_user_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    personalized_text TEXT,
    sent_at TIMESTAMP WITH TIME ZONE,
    replied_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outreach_prospects_campaign ON outreach_prospects (campaign_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_outreach_prospect_campaign_user
    ON outreach_prospects (campaign_id, username)
    WHERE username IS NOT NULL;

CREATE TABLE IF NOT EXISTS outreach_monthly_usage (
    user_id BIGINT NOT NULL,
    month_key VARCHAR(7) NOT NULL,
    sent_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, month_key)
);
