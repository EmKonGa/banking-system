package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A deposit addressed by account <em>number</em>, deliberately, so it matches the transfer:
 * payment-service never resolves an account number to an id — only account-service can — and the
 * id comes back on the result.
 */
public record DepositExecutionRequest(
        String toAccountNumber,
        BigDecimal amount,
        UUID idempotencyKey
) {}
