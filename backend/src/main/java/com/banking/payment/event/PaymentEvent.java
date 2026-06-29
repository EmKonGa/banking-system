package com.banking.payment.event;

import com.banking.payment.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentEvent(
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        UUID fromUserId,
        UUID toUserId,
        BigDecimal amount,
        TransactionType type,
        Instant timestamp
) {}
