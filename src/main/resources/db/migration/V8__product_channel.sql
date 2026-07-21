CREATE TABLE product_channel_posts (
    id                      BIGSERIAL PRIMARY KEY,
    rubric                  VARCHAR(32)  NOT NULL,
    draft_text              TEXT         NOT NULL,
    final_text              TEXT,
    telegram_message_id     INT,
    post_link               VARCHAR(512),
    status                  VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    created_by_telegram_id  BIGINT       NOT NULL,
    published_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_product_channel_posts_status ON product_channel_posts (status, created_at DESC);
