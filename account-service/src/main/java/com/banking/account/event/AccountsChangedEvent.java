package com.banking.account.event;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Published when account rows have been mutated. Consumed after commit to invalidate cached reads.
 * <p>
 * Carries both the accounts that changed and the users whose account lists are now stale — the
 * per-user list cache embeds balances, so it has to be invalidated alongside the account itself.
 * Sets, so a transfer between two accounts owned by the same user collapses to one user id.
 */
public record AccountsChangedEvent(Set<UUID> accountIds, Set<UUID> userIds) {

    public static AccountsChangedEvent of(UUID accountId, UUID userId) {
        return new AccountsChangedEvent(Set.of(accountId), Set.of(userId));
    }

    /**
     * Both sides of a transfer. The destination account usually belongs to a different user, so
     * omitting it would leave the recipient reading a balance from before the money arrived.
     */
    public static AccountsChangedEvent ofTransfer(UUID fromAccountId, UUID fromUserId,
                                                  UUID toAccountId, UUID toUserId) {
        // Built by insertion rather than Set.of(..), which throws on duplicates — a user moving
        // money between two of their own accounts yields fromUserId == toUserId.
        return new AccountsChangedEvent(
                setOf(fromAccountId, toAccountId),
                setOf(fromUserId, toUserId));
    }

    private static Set<UUID> setOf(UUID first, UUID second) {
        Set<UUID> set = new LinkedHashSet<>();
        set.add(first);
        set.add(second);
        return set;
    }
}
