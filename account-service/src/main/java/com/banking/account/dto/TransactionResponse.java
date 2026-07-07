package com.banking.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        String type,
        String status,
        String description,
        Instant createdAt
) {}
