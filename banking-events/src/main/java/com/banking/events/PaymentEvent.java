package com.banking.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentEvent(
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        String fromAccountNumber,
        String toAccountNumber,
        UUID fromUserId,
        UUID toUserId,
        BigDecimal amount,
        BigDecimal fromAccountBalance,
        BigDecimal toAccountBalance,
        String transactionType,
        Instant timestamp
) {}
