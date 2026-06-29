package com.banking.account.dto;

import com.banking.account.entity.AccountType;
import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(
        @NotNull(message = "Account type is required") AccountType type
) {}
