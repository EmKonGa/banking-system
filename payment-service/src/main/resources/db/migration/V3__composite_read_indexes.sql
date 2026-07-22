-- The transaction list queries filter on a user (or account) and order by created_at DESC:
--   WHERE from_user_id = ? OR to_user_id = ? ORDER BY created_at DESC
--
-- With only single-column indexes the planner has no plan that both filters and orders. It either
-- BitmapOrs the two user indexes and sorts the whole match set to return one page, or walks
-- idx_transactions_created_at backwards filtering rows — which for a low-volume user scans an
-- ever-growing share of the table as *other* users accumulate history.
--
-- These composites make each side of the OR an index-ordered scan, so the LIMIT can stop early.
CREATE INDEX IF NOT EXISTS idx_tx_from_user_created    ON transactions(from_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_to_user_created      ON transactions(to_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_from_account_created ON transactions(from_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_to_account_created   ON transactions(to_account_id, created_at DESC);

-- The single-column indexes these replace are now redundant: a composite on (col, created_at)
-- serves every lookup a plain (col) index served, because col is the leading column.
DROP INDEX IF EXISTS idx_transactions_from_user_id;
DROP INDEX IF EXISTS idx_transactions_to_user_id;
DROP INDEX IF EXISTS idx_transactions_from_account_id;
DROP INDEX IF EXISTS idx_transactions_to_account_id;
