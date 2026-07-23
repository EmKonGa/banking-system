-- Transfer intent.
--
-- payment-service asks account-service to move money over HTTP, then records the ledger row in its
-- own database. Those are two transactions in two services: a crash in between left money moved
-- with no evidence in payment-service that a transfer was ever attempted — nothing to retry from
-- and nothing to reconcile against.
--
-- The fix is to commit the intent *before* the money moves, so the failure always leaves a trace.

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS idempotency_key UUID;

-- Existing rows predate the column. They are all settled transfers, so a synthetic key preserves
-- history while letting the constraints below apply.
UPDATE transactions SET idempotency_key = gen_random_uuid() WHERE idempotency_key IS NULL;

ALTER TABLE transactions ALTER COLUMN idempotency_key SET NOT NULL;

-- The client's key is the deduplication point for the whole flow. account-service already dedupes
-- the *money* on it; without this constraint a retried submit still wrote a second ledger row and
-- a second outbox event for one movement of money, double-counting the transfer in history.
ALTER TABLE transactions ADD CONSTRAINT uk_transactions_idempotency_key UNIQUE (idempotency_key);

-- A PENDING intent does not know the destination account id yet: the caller supplies an account
-- *number*, and only account-service can resolve it to an id, which it reports back on execution.
ALTER TABLE transactions ALTER COLUMN to_account_id DROP NOT NULL;

-- Same shape and rationale as idx_outbox_pending: the recovery poller only ever scans PENDING
-- rows, which are rare and short-lived, so a partial index stays tiny regardless of history size.
CREATE INDEX IF NOT EXISTS idx_transactions_pending ON transactions(created_at ASC)
    WHERE status = 'PENDING';
