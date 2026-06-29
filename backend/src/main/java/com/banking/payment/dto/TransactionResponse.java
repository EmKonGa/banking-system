package com.banking.payment.dto;

import com.banking.payment.entity.Transaction;
import com.banking.payment.entity.TransactionStatus;
import com.banking.payment.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        TransactionType type,
        TransactionStatus status,
        String description,
        Instant createdAt
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getFromAccount() != null ? t.getFromAccount().getId() : null,
                t.getToAccount().getId(),
                t.getAmount(),
                t.getType(),
                t.getStatus(),
                t.getDescription(),
                t.getCreatedAt()
        );
    }
}
