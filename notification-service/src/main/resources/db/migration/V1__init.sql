CREATE TABLE IF NOT EXISTS notifications (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL,
    message    VARCHAR(255) NOT NULL,
    type       VARCHAR(50)  NOT NULL,
    read       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ
);
