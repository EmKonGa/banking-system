CREATE INDEX IF NOT EXISTS idx_transactions_from_user_id    ON transactions(from_user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_to_user_id      ON transactions(to_user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_from_account_id ON transactions(from_account_id);
CREATE INDEX IF NOT EXISTS idx_transactions_to_account_id   ON transactions(to_account_id);
CREATE INDEX IF NOT EXISTS idx_transactions_created_at      ON transactions(created_at DESC);

-- Partial index: only PENDING rows are scanned by OutboxPoller, so filter to keep it small.
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_events(created_at ASC)
    WHERE status = 'PENDING';
