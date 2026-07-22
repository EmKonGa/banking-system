package com.banking.account.cache;

import com.banking.account.dto.AccountResponse;
import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.entity.AccountType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Cache-side view of an {@link Account}.
 * <p>
 * Carries {@code userId} — which {@link AccountResponse} deliberately does not expose — so the
 * ownership check in {@code AccountService.findOwnedAccount} can run against a cache hit without
 * going back to the database.
 */
public record CachedAccount(
        UUID id,
        UUID userId,
        String accountNumber,
        BigDecimal balance,
        AccountType type,
        AccountStatus status,
        Instant createdAt
) {
    public static CachedAccount from(Account account) {
        return new CachedAccount(
                account.getId(),
                account.getUserId(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getType(),
                account.getStatus(),
                account.getCreatedAt()
        );
    }

    public AccountResponse toResponse() {
        return new AccountResponse(id, accountNumber, balance, type, status, createdAt);
    }
}
