package com.banking.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Addresses the account by number rather than id, matching {@link TransferRequest}: payment-service
 * cannot resolve an account number, and the intent has to be writable before account-service is
 * asked anything.
 */
public record DepositRequest(
        @NotBlank String toAccountNumber,
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be positive")
        @Digits(integer = 15, fraction = 4) BigDecimal amount,
        String description,
        @NotNull(message = "idempotencyKey is required; generate one per deposit attempt and reuse it on retry")
        UUID idempotencyKey
) {}
