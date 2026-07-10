CREATE TABLE analysis_snapshots (
    request_id              BIGINT PRIMARY KEY REFERENCES analysis_requests (id) ON DELETE CASCADE,
    avg_views               INT,
    avg_engagement_rate     DECIMAL(6, 4),
    views_delta_percent     DECIMAL(6, 2),
    post_count              INT,
    best_publish_slots      JSONB,
    avoid_slots             JSONB,
    top_posts               JSONB,
    worst_posts             JSONB,
    working_topics          JSONB,
    frequency_recommendation VARCHAR(255),
    raw_metrics             JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE content_ideas (
    id              BIGSERIAL PRIMARY KEY,
    request_id      BIGINT       NOT NULL REFERENCES analysis_requests (id) ON DELETE CASCADE,
    sort_order      SMALLINT     NOT NULL,
    title           VARCHAR(512) NOT NULL,
    reason          TEXT         NOT NULL,
    format          VARCHAR(64),
    suggested_day   VARCHAR(32),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_content_ideas_request_order ON content_ideas (request_id, sort_order);

CREATE TABLE generated_posts (
    id              BIGSERIAL PRIMARY KEY,
    request_id      BIGINT       NOT NULL REFERENCES analysis_requests (id) ON DELETE CASCADE,
    idea_id         BIGINT       NOT NULL REFERENCES content_ideas (id) ON DELETE CASCADE,
    sort_order      SMALLINT     NOT NULL,
    variant_a       TEXT         NOT NULL,
    variant_b       TEXT,
    variant_c       TEXT,
    headlines       JSONB,
    cta             VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE published_posts (
    id                      BIGSERIAL PRIMARY KEY,
    generated_post_id       BIGINT       NOT NULL REFERENCES generated_posts (id) ON DELETE CASCADE,
    user_id                 BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel_id              BIGINT       NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    variant_used            CHAR(1)      NOT NULL,
    final_text              TEXT         NOT NULL,
    telegram_message_id     INT,
    post_link               VARCHAR(512),
    published_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
