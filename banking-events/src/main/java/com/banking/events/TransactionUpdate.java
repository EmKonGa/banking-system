package com.banking.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionUpdate(
        UUID transactionId,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        String type,
        Instant timestamp
) {}
