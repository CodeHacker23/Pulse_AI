CREATE TABLE IF NOT EXISTS product_trusted_sources (
    id              BIGSERIAL PRIMARY KEY,
    label           VARCHAR(128) NOT NULL,
    source_type     VARCHAR(16)  NOT NULL,
    url_or_username VARCHAR(512) NOT NULL,
    trust_level     SMALLINT     NOT NULL DEFAULT 5,
    notes           TEXT,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS product_style_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    summary         TEXT         NOT NULL,
    reference_list  TEXT,
    post_samples    TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_product_style_created ON product_style_snapshots (created_at DESC);

INSERT INTO product_trusted_sources (label, source_type, url_or_username, trust_level, notes) 
SELECT 'Telegram Blog', 'URL', 'https://telegram.org/blog', 10, 'Официальные обновления Telegram'
WHERE NOT EXISTS (SELECT 1 FROM product_trusted_sources WHERE url_or_username = 'https://telegram.org/blog');

INSERT INTO product_trusted_sources (label, source_type, url_or_username, trust_level, notes)
SELECT 'Pulse AI бот', 'BOT', 'https://t.me/Pulsse_AI_bot', 10, 'Наш продукт'
WHERE NOT EXISTS (SELECT 1 FROM product_trusted_sources WHERE url_or_username = 'https://t.me/Pulsse_AI_bot');
