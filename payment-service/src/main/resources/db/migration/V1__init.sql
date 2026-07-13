CREATE TABLE IF NOT EXISTS transactions (
    id                  UUID           PRIMARY KEY,
    from_account_id     UUID,
    to_account_id       UUID           NOT NULL,
    from_account_number VARCHAR(255),
    to_account_number   VARCHAR(255)   NOT NULL,
    from_user_id        UUID,
    to_user_id          UUID,
    amount              NUMERIC(19, 4) NOT NULL,
    type                VARCHAR(50)    NOT NULL,
    status              VARCHAR(50)    NOT NULL,
    description         VARCHAR(255),
    created_at          TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS outbox_events (
    id            UUID         PRIMARY KEY,
    topic         VARCHAR(255) NOT NULL,
    aggregate_id  VARCHAR(255) NOT NULL,
    payload       TEXT         NOT NULL,
    status        VARCHAR(50)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    processed_at  TIMESTAMPTZ,
    retry_count   INTEGER      NOT NULL DEFAULT 0,
    last_error    VARCHAR(1000),
    next_retry_at TIMESTAMPTZ
);
