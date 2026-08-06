-- When the auditor last completed a full sweep.
--
-- Without this, a sweeper that has stopped working is indistinguishable from a clean system: the
-- findings gauges read from this database, so if the register was clean when sweeps stopped they
-- keep reporting zero for as long as nobody looks. Every failure mode is silent that way — the
-- key-set ceiling, a dependency outage, a bad deploy.
--
-- Deliberately not seeded. No row means no sweep has ever completed, which the gauge reports as
-- epoch 0 — maximally stale, so a service that has never worked alerts exactly like one that has
-- stopped working.

CREATE TABLE IF NOT EXISTS reconciliation_sweep_state (
    id                       SMALLINT     PRIMARY KEY,
    last_successful_sweep_at TIMESTAMPTZ  NOT NULL,
    -- One row, enforced rather than assumed: two rows would make "when did the sweep last finish"
    -- ambiguous, and the gauge would answer with whichever the database happened to return.
    CONSTRAINT ck_sweep_state_singleton CHECK (id = 1)
);
