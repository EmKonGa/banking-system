-- findByUserIdOrderByCreatedAtDesc filters on user_id and orders by created_at DESC. The existing
-- idx_notifications_user_id can filter but not order, leaving a sort over the user's whole inbox
-- to return one page. This composite serves both.
CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notifications(user_id, created_at DESC);

-- Redundant now: user_id is the leading column of the composite above.
DROP INDEX IF EXISTS idx_notifications_user_id;

-- idx_notifications_user_read is kept — markAllReadByUserId filters on (user_id, read = false),
-- which the composite above does not serve.
