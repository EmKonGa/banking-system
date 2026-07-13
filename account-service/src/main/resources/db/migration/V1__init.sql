CREATE TABLE IF NOT EXISTS accounts (
    id             UUID           PRIMARY KEY,
    user_id        UUID           NOT NULL,
    account_number VARCHAR(255)   NOT NULL UNIQUE,
    balance        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    type           VARCHAR(50)    NOT NULL,
    status         VARCHAR(50)    NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS account_transfer_log (
    idempotency_key    UUID           PRIMARY KEY,
    from_account_id    UUID           NOT NULL,
    to_account_id      UUID           NOT NULL,
    from_user_id       UUID           NOT NULL,
    to_user_id         UUID           NOT NULL,
    from_account_number VARCHAR(255)  NOT NULL,
    to_account_number  VARCHAR(255)   NOT NULL,
    from_balance       NUMERIC(19, 4) NOT NULL,
    to_balance         NUMERIC(19, 4) NOT NULL,
    created_at         TIMESTAMPTZ
);
