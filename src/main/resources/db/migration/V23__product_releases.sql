CREATE TABLE IF NOT EXISTS product_releases (
    id              BIGSERIAL PRIMARY KEY,
    version         VARCHAR(32)  NOT NULL,
    title           VARCHAR(256) NOT NULL,
    bullets         TEXT         NOT NULL,
    category        VARCHAR(16)  NOT NULL DEFAULT 'UPDATE',
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    released_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    posted_at       TIMESTAMPTZ,
    channel_post_id BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_product_releases_status ON product_releases (status, released_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_releases_version ON product_releases (version);
