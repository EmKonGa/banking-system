-- Deposits join the money-movement log.
--
-- account_transfer_log is what makes "did money move for this idempotency key?" answerable: the row
-- is written in the same transaction as the balance change, so its absence is conclusive. That is
-- the question payment-service's recovery poller asks, and until now only transfers could answer
-- it — deposits mutated a balance and recorded nothing anywhere.
--
-- Rather than add a second log with a second recovery path, deposits reuse this one. The table name
-- is now narrower than its contents; renaming it would churn the entity, the repository and the
-- /internal/accounts/transfers/{key} endpoint for cosmetics, so it stays.

-- A deposit has no source account: the money comes from outside the system.
ALTER TABLE account_transfer_log ALTER COLUMN from_account_id     DROP NOT NULL;
ALTER TABLE account_transfer_log ALTER COLUMN from_user_id        DROP NOT NULL;
ALTER TABLE account_transfer_log ALTER COLUMN from_account_number DROP NOT NULL;
ALTER TABLE account_transfer_log ALTER COLUMN from_balance        DROP NOT NULL;

-- Existing rows are all transfers. The default keeps them valid and lets the NOT NULL apply
-- immediately; new rows always set it explicitly.
ALTER TABLE account_transfer_log
    ADD COLUMN IF NOT EXISTS type VARCHAR(50) NOT NULL DEFAULT 'TRANSFER';
