-- Backs payment_outbox_abandoned_events, which counts this status on every Prometheus scrape (15s).
--
-- outbox_events is append-only: PUBLISHED rows are never deleted, so without this the gauge is a
-- seq scan over a table that only grows, four times a minute, forever. A monitoring query that gets
-- slower the longer the system runs is a poor trade for the thing it monitors.
--
-- Partial, and on created_at, for the same two reasons idx_outbox_pending is. Partial because
-- PUBLISHED is almost every row and nothing queries it, so indexing the whole table to find the rare
-- status wastes most of the index. On created_at because the next question after "how many were
-- abandoned" is always "since when", and an index-only scan answers both.
CREATE INDEX IF NOT EXISTS idx_outbox_failed ON outbox_events(created_at ASC)
    WHERE status = 'FAILED';
