package com.banking.account.cache;

import com.banking.account.event.AccountsChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Invalidates cached account reads once a mutation has committed.
 * <p>
 * Driven by {@link AccountsChangedEvent} rather than direct calls, so the services that move money
 * announce what changed without depending on the cache at all — same pattern as
 * {@code OutboxPoller.onTransferCommitted}.
 * <p>
 * <b>Why after commit.</b> Evicting inside the transaction would open a window where a concurrent
 * reader misses the cache, reads the not-yet-committed old balance, and re-populates the entry —
 * leaving it stale precisely when it must not be. {@code AFTER_COMMIT} does not fire on rollback,
 * which is correct: a rolled-back transaction left the database unchanged, so nothing is stale.
 * <p>
 * Uses {@link CacheManager} directly rather than {@code @CacheEvict} because one event evicts a
 * variable number of keys across two caches, which the annotation's fixed key expression cannot
 * express.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountCacheEvictor {

    private final CacheManager cacheManager;

    /**
     * {@code fallbackExecution = true} matters: without it the listener silently does nothing when
     * no transaction is active, and an eviction published from a non-transactional path would
     * vanish with no error.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountsChanged(AccountsChangedEvent event) {
        event.accountIds().forEach(accountId -> evict(CacheNames.ACCOUNT_BY_ID, accountId));
        event.userIds().forEach(userId -> evict(CacheNames.ACCOUNTS_BY_USER, userId));
    }

    private void evict(String cacheName, UUID key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return;
        }
        try {
            cache.evictIfPresent(key);
        } catch (RuntimeException e) {
            // Fail open, consistent with CacheConfig's error handler. Direct CacheManager use does
            // not route through it, so the guard is repeated here. Logged at error because a
            // dropped eviction is how a stale balance survives — bounded only by the TTL.
            log.error("Cache eviction FAILED cache={} key={}: {} — stale until TTL expiry",
                    cacheName, key, e.getMessage());
        }
    }
}
