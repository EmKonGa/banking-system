package com.banking.reconciliation.entity;

/**
 * The lifecycle of a finding, which exists because there is no consistent cut across two databases.
 *
 * <p>Balances and the ledger are read at different instants, so a transfer committing mid-sweep
 * makes an account look wrong when nothing is. Rather than pretend to a distributed snapshot, a
 * discrepancy has to be seen by two consecutive sweeps before it is believed: an artefact of
 * in-flight money resolves itself, a real one persists. Same asymmetry the transfer recovery poller
 * uses — the irreversible call is the one that must wait.
 */
public enum FindingStatus {

    /** Seen once. Very likely a movement that was in flight while the sweep read the two sides. */
    SUSPECTED,

    /** Seen again on a later sweep. This is what is worth waking someone for. */
    CONFIRMED,

    /** No longer detected. Kept rather than deleted — that it happened at all is the signal. */
    RESOLVED
}
