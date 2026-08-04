package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One account's authoritative balance, as account-service holds it.
 *
 * <p>Read from account-service's own tables rather than derived from the event stream, deliberately.
 * A reconciler that rebuilt balances from the same events the ledger consumes would share a failure
 * mode with the thing it audits: an event that was never published would be invisible to both, and
 * the two would agree perfectly about a system that had lost money. Independence is the point.
 */
public record AccountBalanceSnapshot(
        UUID accountId,
        String accountNumber,
        BigDecimal balance,
        String status
) {}
