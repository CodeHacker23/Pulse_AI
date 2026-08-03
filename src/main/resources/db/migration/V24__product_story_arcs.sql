CREATE TABLE IF NOT EXISTS product_story_arcs (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(256) NOT NULL,
    premise         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_by      BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS product_story_beats (
    id                  BIGSERIAL PRIMARY KEY,
    arc_id              BIGINT       NOT NULL REFERENCES product_story_arcs(id) ON DELETE CASCADE,
    beat_index          SMALLINT     NOT NULL,
    beat_key            VARCHAR(32)  NOT NULL,
    title               VARCHAR(256) NOT NULL,
    outline             TEXT         NOT NULL,
    draft_text          TEXT,
    status              VARCHAR(16)  NOT NULL DEFAULT 'PLANNED',
    channel_post_id     BIGINT,
    telegram_message_id INT,
    post_link           VARCHAR(512),
    scheduled_for       TIMESTAMPTZ,
    published_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_story_beat UNIQUE (arc_id, beat_index)
);

CREATE INDEX IF NOT EXISTS idx_story_beats_arc ON product_story_beats (arc_id, beat_index);
CREATE INDEX IF NOT EXISTS idx_story_beats_sched ON product_story_beats (status, scheduled_for);
