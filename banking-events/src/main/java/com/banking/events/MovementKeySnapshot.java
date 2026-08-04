package com.banking.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The identity of one committed money movement, as account-service recorded it.
 *
 * <p>Carries no amount because {@code account_transfer_log} does not store one — it records the
 * resulting balances instead. That is why the amount-based invariant is computed against the ledger
 * and this snapshot is used only for the set comparison: does a movement that account-service
 * committed have a settled ledger row, and vice versa.
 */
public record MovementKeySnapshot(
        UUID idempotencyKey,
        String type,
        Instant createdAt
) {}
