package com.banking.account.cache;

import com.banking.account.repository.AccountRepository;
import com.banking.common.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Cached account reads.
 * <p>
 * A separate bean on purpose: Spring's cache advice is proxy-based, so {@code @Cacheable} methods
 * called from within {@code AccountService} itself would silently bypass the cache.
 * <p>
 * Neither method performs an authorization check — callers must. See
 * {@code AccountService.findOwnedAccount}.
 */
@Service
@RequiredArgsConstructor
public class AccountReader {

    private final AccountRepository accountRepository;

    /**
     * A missing account throws rather than returning null, so nothing negative is cached — an id
     * that does not exist keeps hitting the database instead of pinning a "not found" entry that
     * would outlive the account being created.
     */
    @Cacheable(cacheNames = CacheNames.ACCOUNT_BY_ID, key = "#id", sync = true)
    public CachedAccount byId(UUID id) {
        return accountRepository.findById(id)
                .map(CachedAccount::from)
                .orElseThrow(() -> new AppException("Account not found", HttpStatus.NOT_FOUND));
    }

    /**
     * Returns <em>all</em> of the user's accounts, CLOSED included, so the cache key stays a plain
     * user id. Callers filter by status — otherwise the admin/non-admin distinction would have to
     * enter the key and every eviction would need to clear both variants.
     */
    @Cacheable(cacheNames = CacheNames.ACCOUNTS_BY_USER, key = "#userId", sync = true)
    public List<CachedAccount> byUser(UUID userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(CachedAccount::from)
                .toList();
    }
}
