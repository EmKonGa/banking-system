package com.banking.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * When the sweeper last completed a pass, so that a sweeper which has stopped working can be told
 * apart from a system with nothing wrong with it.
 *
 * <p>The findings gauges are read out of this database, which means they hold their last value when
 * sweeps stop happening. A register that was clean at that moment reports clean indefinitely — the
 * auditor dies quietly and its own metric vouches for the system it is no longer checking. This row
 * is what makes that visible, and it is written in the same transaction as the findings so it can
 * only advance for a pass that actually recorded something.
 *
 * <p>Persisted rather than held in a field, for the failure it has to survive: a service crash-
 * looping before it can finish a sweep would, with in-memory state, report itself freshly started
 * every time and never look stale — hiding precisely the outage being watched for.
 */
@Entity
@Table(name = "reconciliation_sweep_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SweepState {

    /** The only id this table ever holds; the CHECK constraint in V2 enforces it. */
    public static final short SINGLETON_ID = 1;

    @Id
    private short id;

    @Column(name = "last_successful_sweep_at", nullable = false)
    private Instant lastSuccessfulSweepAt;
}
