package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferExecutionResult(
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal fromBalance,
        BigDecimal toBalance,
        UUID fromUserId,
        UUID toUserId,
        UUID toAccountId
) {}
