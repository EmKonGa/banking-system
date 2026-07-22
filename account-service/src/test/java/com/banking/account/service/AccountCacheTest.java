package com.banking.account.service;

import com.banking.account.cache.AccountReader;
import com.banking.account.cache.CachedAccount;
import com.banking.account.client.PaymentServiceClient;
import com.banking.account.dto.AccountResponse;
import com.banking.account.entity.AccountStatus;
import com.banking.account.entity.AccountType;
import com.banking.account.event.AccountsChangedEvent;
import com.banking.account.repository.AccountRepository;
import com.banking.common.exception.AppException;
import com.banking.common.security.JwtPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Guards the two ways a read cache can go wrong in front of account data:
 * serving one user's balances to another, and serving a balance that a mutation should have
 * invalidated.
 *
 * <p>These are the checks that do not need Postgres — the money-path itself is covered by
 * {@link TransferConcurrencyStressTest}.
 */
@ExtendWith(MockitoExtension.class)
class AccountCacheTest {

    @Mock AccountRepository accountRepository;
    @Mock PaymentServiceClient paymentServiceClient;
    @Mock AccountReader accountReader;
    @Mock ApplicationEventPublisher events;

    @InjectMocks AccountService accountService;

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID INTRUDER = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID userId, String role) {
        JwtPrincipal principal = new JwtPrincipal(userId, userId + "@example.com", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private CachedAccount cached(UUID id, UUID userId, AccountStatus status) {
        return new CachedAccount(id, userId, "000000000001", new BigDecimal("100.0000"),
                AccountType.SAVINGS, status, Instant.now());
    }

    /**
     * The cache is keyed by account id alone, so a hit is shared across callers. The ownership
     * check therefore has to run on the returned value every time — if it were skipped on a hit,
     * any authenticated user could read any account by guessing its id.
     */
    @Test
    void cacheHitForAnotherUsersAccountIsStillRejected() {
        UUID accountId = UUID.randomUUID();
        when(accountReader.byId(accountId)).thenReturn(cached(accountId, OWNER, AccountStatus.ACTIVE));
        authenticateAs(INTRUDER, "USER");

        assertThatThrownBy(() -> accountService.getAccount(accountId))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Account not found")
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ownerReadsTheirOwnCachedAccount() {
        UUID accountId = UUID.randomUUID();
        when(accountReader.byId(accountId)).thenReturn(cached(accountId, OWNER, AccountStatus.ACTIVE));
        authenticateAs(OWNER, "USER");

        AccountResponse response = accountService.getAccount(accountId);

        assertThat(response.id()).isEqualTo(accountId);
        assertThat(response.balance()).isEqualByComparingTo("100.0000");
    }

    /**
     * The cached list holds every account including CLOSED ones, keeping the key a plain user id.
     * The status filter has to be applied on the way out instead.
     */
    @Test
    void closedAccountsAreFilteredForNonAdminsButVisibleToAdmins() {
        UUID open = UUID.randomUUID();
        UUID closed = UUID.randomUUID();
        when(accountReader.byUser(OWNER)).thenReturn(List.of(
                cached(open, OWNER, AccountStatus.ACTIVE),
                cached(closed, OWNER, AccountStatus.CLOSED)));

        authenticateAs(OWNER, "USER");
        assertThat(accountService.listMyAccounts()).extracting(AccountResponse::id)
                .containsExactly(open);

        authenticateAs(OWNER, "ADMIN");
        assertThat(accountService.listMyAccounts()).extracting(AccountResponse::id)
                .containsExactlyInAnyOrder(open, closed);
    }

    /**
     * A user moving money between two of their own accounts produces the same user id on both
     * sides. Building the event with {@code Set.of(a, b)} would throw on the duplicate and blow up
     * the transfer itself, so the collapse to a single id has to be deliberate.
     */
    @Test
    void transferBetweenOwnAccountsCollapsesToOneUserId() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        AccountsChangedEvent event = AccountsChangedEvent.ofTransfer(from, OWNER, to, OWNER);

        assertThat(event.accountIds()).containsExactlyInAnyOrder(from, to);
        assertThat(event.userIds()).containsExactly(OWNER);
    }

    @Test
    void transferBetweenDifferentUsersInvalidatesBothSides() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        AccountsChangedEvent event = AccountsChangedEvent.ofTransfer(from, OWNER, to, INTRUDER);

        assertThat(event.accountIds()).containsExactlyInAnyOrder(from, to);
        assertThat(event.userIds()).containsExactlyInAnyOrder(OWNER, INTRUDER);
    }
}
