-- Рекламные сделки: интерес → бриф → согласование → оплата (эскроу позже).
CREATE TABLE IF NOT EXISTS ad_deals (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    owner_channel_id    BIGINT,
    placement_id        BIGINT,
    target_username     VARCHAR(128) NOT NULL,
    status              VARCHAR(24)  NOT NULL DEFAULT 'INTEREST',
    pin_format          VARCHAR(64),
    price_admin_rub     INT,
    price_client_rub    INT,
    commission_percent  SMALLINT     NOT NULL DEFAULT 20,
    creative_draft      TEXT,
    admin_notes         TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ad_deals_user ON ad_deals (user_id, created_at DESC);
