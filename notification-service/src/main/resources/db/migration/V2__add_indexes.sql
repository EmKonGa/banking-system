CREATE INDEX IF NOT EXISTS idx_notifications_user_id    ON notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_user_read  ON notifications(user_id, read);
CREATE INDEX IF NOT EXISTS idx_notifications_created_at ON notifications(created_at DESC);
