package com.banking.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The identity and settled state of one ledger row.
 *
 * <p>Status is included rather than filtered server-side so the reconciler can tell the two
 * directions apart: a movement account-service committed whose ledger row says {@code FAILED} is a
 * bad write-off, while one with no ledger row at all is a lost settlement. Those want different
 * findings, and collapsing them server-side would hide the difference.
 */
public record LedgerKeySnapshot(
        UUID transactionId,
        UUID idempotencyKey,
        String status,
        Instant createdAt
) {}
