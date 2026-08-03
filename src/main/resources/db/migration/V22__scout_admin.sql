-- Scout action log + outreach message templates (Wave 2).

CREATE TABLE IF NOT EXISTS scout_action_log (
    id BIGSERIAL PRIMARY KEY,
    scout_account_id BIGINT,
    user_id BIGINT,
    action VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OK',
    payload VARCHAR(1024),
    error_text VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scout_action_log_created ON scout_action_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_scout_action_log_account ON scout_action_log (scout_account_id, created_at DESC);

CREATE TABLE IF NOT EXISTS outreach_message_templates (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    scenario VARCHAR(16) NOT NULL DEFAULT 'INVITE',
    name VARCHAR(128) NOT NULL,
    body TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outreach_templates_user ON outreach_message_templates (user_id, scenario, active);
