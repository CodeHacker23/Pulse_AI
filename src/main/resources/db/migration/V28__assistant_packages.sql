-- P0.9: подписка Pulse Ассистент + допы ЛС (отдельно от пакетов разборов)

ALTER TABLE packages ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'ANALYSIS';
ALTER TABLE packages ADD COLUMN IF NOT EXISTS dm_quota INT NOT NULL DEFAULT 0;
ALTER TABLE packages ADD COLUMN IF NOT EXISTS parse_quota INT NOT NULL DEFAULT 0;
ALTER TABLE packages ADD COLUMN IF NOT EXISTS includes_find_audience BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE packages SET kind = 'ANALYSIS' WHERE code IN ('START', 'CONTENT', 'PRO');

INSERT INTO packages (code, name, request_count, price_rub, stars_amount, is_active, sort_order,
                      perk_choices_count, includes_priority, kind, dm_quota, parse_quota, includes_find_audience)
VALUES
    ('ASSIST', 'Ассистент', 0, 3990, 2000, TRUE, 10, 0, FALSE, 'ASSISTANT', 100, 2, FALSE),
    ('ASSIST_PLUS', 'Ассистент+', 0, 6990, 3500, TRUE, 11, 0, FALSE, 'ASSISTANT', 500, 5, TRUE),
    ('ASSIST_PRO', 'Ассистент Pro', 0, 9990, 5000, TRUE, 12, 0, TRUE, 'ASSISTANT', 1000, 10, TRUE),
    ('LS_100', '+100 ЛС', 0, 990, 500, TRUE, 20, 0, FALSE, 'LS_TOPUP', 100, 0, FALSE),
    ('LS_500', '+500 ЛС', 0, 3490, 1750, TRUE, 21, 0, FALSE, 'LS_TOPUP', 500, 0, FALSE),
    ('LS_1000', '+1000 ЛС', 0, 5990, 3000, TRUE, 22, 0, FALSE, 'LS_TOPUP', 1000, 0, FALSE)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    request_count = EXCLUDED.request_count,
    price_rub = EXCLUDED.price_rub,
    stars_amount = EXCLUDED.stars_amount,
    is_active = EXCLUDED.is_active,
    sort_order = EXCLUDED.sort_order,
    perk_choices_count = EXCLUDED.perk_choices_count,
    includes_priority = EXCLUDED.includes_priority,
    kind = EXCLUDED.kind,
    dm_quota = EXCLUDED.dm_quota,
    parse_quota = EXCLUDED.parse_quota,
    includes_find_audience = EXCLUDED.includes_find_audience;
