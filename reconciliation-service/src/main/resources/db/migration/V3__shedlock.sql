-- The lock that keeps the sweep a singleton across replicas.
--
-- Two concurrent sweeps do not merely duplicate work — they blind the register. resolveUnseen()
-- resolves every unresolved finding absent from *this* sweep's observation set, and one replica's
-- set never contains the other's observations. So A resolves what B just recorded, B sees it again
-- and (correctly, by the recurrence rule) resets it to SUSPECTED with times_seen = 1. times_seen
-- never reaches 2, nothing is ever promoted to CONFIRMED, and CONFIRMED is the only thing the
-- alerting gauge counts — while both replicas keep writing the liveness marker, so the staleness
-- alert stays quiet too. A permanently blind auditor reporting itself healthy.
--
-- It lives in this database rather than in Redis so the lock shares a fate with the register it
-- protects: a Redis outage must not be able to let two sweeps run against a healthy Postgres.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
