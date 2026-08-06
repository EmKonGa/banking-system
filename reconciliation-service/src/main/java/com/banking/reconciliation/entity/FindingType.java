package com.banking.reconciliation.entity;

public enum FindingType {

    /**
     * An account's balance does not equal credits minus debits over its settled ledger rows.
     * The headline invariant: money moved without being recorded, or was recorded without moving.
     */
    BALANCE_MISMATCH,

    /**
     * account-service committed a movement for an idempotency key, but payment-service has no
     * settled ledger row for it — either none at all, or one marked FAILED. The second case is a
     * write-off of a transfer whose money actually moved.
     */
    MOVEMENT_NOT_LEDGERED,

    /**
     * payment-service has a COMPLETED ledger row whose idempotency key account-service never
     * committed. The ledger claims money moved that did not.
     */
    LEDGER_WITHOUT_MOVEMENT,

    /**
     * An intent still PENDING long past the write-off window. Not money lost — the mechanism that
     * decides whether it was is itself stuck, which is otherwise entirely silent.
     */
    STUCK_INTENT
}
