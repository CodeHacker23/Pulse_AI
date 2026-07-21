-- Книга возражений + журнал выводов агента-продавца (P0.5).

ALTER TABLE channels ADD COLUMN IF NOT EXISTS sales_objections TEXT;

CREATE TABLE IF NOT EXISTS sales_learnings (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    lead_id BIGINT,
    outcome VARCHAR(16) NOT NULL,
    summary TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sales_learnings_channel
    ON sales_learnings (channel_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sales_learnings_owner
    ON sales_learnings (owner_user_id, created_at DESC);
