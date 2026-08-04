package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the ledger says an account should hold: credits minus debits over settled movements.
 *
 * <p>Aggregated by payment-service rather than by streaming every row to the reconciler — the
 * comparison stays independent either way, and the sum belongs where the data is.
 */
public record LedgerNetSnapshot(
        UUID accountId,
        BigDecimal net
) {}
