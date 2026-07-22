package com.banking.account.cache;

public final class CacheNames {

    /** Single account keyed by account id. */
    public static final String ACCOUNT_BY_ID = "accounts";

    /** Every account owned by a user, keyed by user id — including CLOSED ones, which callers filter. */
    public static final String ACCOUNTS_BY_USER = "accountsByUser";

    private CacheNames() {
    }
}