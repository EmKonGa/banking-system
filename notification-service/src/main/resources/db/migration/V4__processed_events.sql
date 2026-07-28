-- Inbox / deduplication table.
--
-- payment-service's outbox is at-least-once by design: a redelivery after a rebalance, a retry, or
-- a poller republish is expected, not exceptional. The consumer created notifications
-- unconditionally, so every redelivery told the user about the same transfer twice.
--
-- Recording the event id in the *same transaction* as the notifications makes processing idempotent:
-- either both are committed or neither is, so a redelivery finds the row and skips.

CREATE TABLE IF NOT EXISTS processed_events (
    -- PaymentEvent.transactionId. Primary key rather than a plain column: the constraint is the
    -- deduplication mechanism, not the lookup that precedes it.
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- This table grows one row per transfer forever. Pruning is safe once rows are older than the
-- broker's retention (a message that can no longer be redelivered cannot be a duplicate), and this
-- index is what makes that prune cheap when it is worth doing.
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at ON processed_events(processed_at);
