ALTER TABLE packages ADD COLUMN IF NOT EXISTS perk_choices_count SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE packages ADD COLUMN IF NOT EXISTS includes_priority BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE packages SET perk_choices_count = 1, price_rub = 890,  stars_amount = 450,  name = 'Старт'   WHERE code = 'START';
UPDATE packages SET perk_choices_count = 2, price_rub = 1490, stars_amount = 750,  name = 'Оптимал' WHERE code = 'CONTENT';
UPDATE packages SET perk_choices_count = 3, price_rub = 1990, stars_amount = 1000, name = 'Про', includes_priority = TRUE WHERE code = 'PRO';

CREATE TABLE IF NOT EXISTS user_entitlements (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    perk_code          VARCHAR(32)  NOT NULL,
    uses_remaining     INT,
    granted_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at         TIMESTAMPTZ,
    source_payment_id  BIGINT       REFERENCES payments (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_user_entitlements_user ON user_entitlements (user_id, perk_code);
CREATE INDEX IF NOT EXISTS idx_user_entitlements_active ON user_entitlements (user_id, expires_at);

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS perks_remaining_to_pick SMALLINT NOT NULL DEFAULT 0;
