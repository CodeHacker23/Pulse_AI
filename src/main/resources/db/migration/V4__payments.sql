CREATE TABLE packages (
    id              SMALLSERIAL PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(64)  NOT NULL,
    request_count   INT          NOT NULL,
    price_rub       INT          NOT NULL,
    stars_amount    INT,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      SMALLINT     NOT NULL DEFAULT 0
);

INSERT INTO packages (code, name, request_count, price_rub, stars_amount, sort_order) VALUES
    ('START',   'Старт',   10,  990,  500,  1),
    ('CONTENT', 'Контент', 18, 1600,  800,  2),
    ('PRO',     'Про',     30, 2300, 1150,  3);

CREATE TABLE payments (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    package_id          SMALLINT     NOT NULL REFERENCES packages (id),
    provider            VARCHAR(32)  NOT NULL,
    external_id         VARCHAR(255),
    amount_rub          INT          NOT NULL,
    discount_percent    SMALLINT     NOT NULL DEFAULT 0,
    promo_code          VARCHAR(64),
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    requests_credited   INT,
    metadata            JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX idx_payments_user_created ON payments (user_id, created_at DESC);
CREATE INDEX idx_payments_external_id ON payments (external_id);
CREATE INDEX idx_payments_status ON payments (status);

CREATE TABLE balance_transactions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    delta           INT          NOT NULL,
    balance_after   INT          NOT NULL,
    reason          VARCHAR(64)  NOT NULL,
    reference_id    BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_balance_transactions_user ON balance_transactions (user_id, created_at DESC);
