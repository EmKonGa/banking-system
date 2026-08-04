-- Findings from the reconciliation sweep.
--
-- The unique constraint on (type, subject_id) is what makes this a register of *discrepancies*
-- rather than a log of sightings: a mismatch that survives twenty sweeps is one finding seen twenty
-- times. Without it, an alert on open findings would grow with sweep frequency rather than with
-- how much is actually wrong.

CREATE TABLE IF NOT EXISTS reconciliation_findings (
    id            UUID           PRIMARY KEY,
    type          VARCHAR(50)    NOT NULL,
    subject_id    UUID           NOT NULL,
    status        VARCHAR(50)    NOT NULL,
    observed      NUMERIC(19, 4),
    expected      NUMERIC(19, 4),
    detail        VARCHAR(500)   NOT NULL,
    times_seen    INTEGER        NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ,
    last_seen_at  TIMESTAMPTZ    NOT NULL,
    resolved_at   TIMESTAMPTZ,
    CONSTRAINT uk_findings_type_subject UNIQUE (type, subject_id)
);

-- The sweep re-reads every unresolved finding on each pass, and the alerting gauge counts them.
-- Partial, because resolved rows are kept forever and are never part of either query.
CREATE INDEX IF NOT EXISTS idx_findings_unresolved
    ON reconciliation_findings(type, subject_id)
    WHERE status <> 'RESOLVED';
