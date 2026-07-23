package com.banking.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotBlank String toAccountNumber,
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be positive")
        @Digits(integer = 15, fraction = 4) BigDecimal amount,
        String description,
        @NotNull(message = "idempotencyKey is required; generate one per transfer attempt and reuse it on retry")
        UUID idempotencyKey
) {}
