CREATE TABLE scheduled_notifications (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type            VARCHAR(64)  NOT NULL,
    scheduled_at    TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    cancelled       BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_scheduled_notifications_pending
    ON scheduled_notifications (scheduled_at)
    WHERE sent_at IS NULL AND cancelled = FALSE;

CREATE UNIQUE INDEX idx_scheduled_notifications_user_type_pending
    ON scheduled_notifications (user_id, type)
    WHERE sent_at IS NULL AND cancelled = FALSE;
