CREATE INDEX IF NOT EXISTS idx_accounts_user_id        ON accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_accounts_status          ON accounts(status);
CREATE INDEX IF NOT EXISTS idx_transfer_log_from_account ON account_transfer_log(from_account_id);
CREATE INDEX IF NOT EXISTS idx_transfer_log_to_account   ON account_transfer_log(to_account_id);
