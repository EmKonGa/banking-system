package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferExecutionRequest(
        UUID fromAccountId,
        String toAccountNumber,
        BigDecimal amount,
        UUID idempotencyKey,
        UUID fromUserId
) {}
