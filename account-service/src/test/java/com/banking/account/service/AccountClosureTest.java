package com.banking.account.service;

import com.banking.account.cache.AccountReader;
import com.banking.account.client.PaymentServiceClient;
import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.entity.AccountType;
import com.banking.account.event.AccountsChangedEvent;
import com.banking.account.repository.AccountRepository;
import com.banking.common.exception.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Closing an account must not be a way to make money disappear.
 *
 * <p>Closing used to be unconditional, which orphaned any remaining balance: the money stays on a
 * row that nothing can reach — every balance-changing query is predicated on
 * {@code status = 'ACTIVE'} — while no ledger entry records it leaving. Reconciliation comparing
 * balances against the ledger would report that as money destroyed, correctly.
 */
@ExtendWith(MockitoExtension.class)
class AccountClosureTest {

    @Mock AccountRepository accountRepository;
    @Mock PaymentServiceClient paymentServiceClient;
    @Mock AccountReader accountReader;
    @Mock ApplicationEventPublisher events;

    @InjectMocks AccountService accountService;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private Account account(String balance) {
        return Account.builder()
                .id(ACCOUNT_ID).userId(OWNER).accountNumber("000000000002")
                .balance(new BigDecimal(balance)).type(AccountType.SAVINGS)
                .status(AccountStatus.ACTIVE)
                .build();
    }

    @Test
    void anEmptyAccountClosesAndItsCacheEntriesAreEvicted() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account("0.0000")));
        when(accountRepository.closeIfEmpty(ACCOUNT_ID)).thenReturn(1);

        accountService.closeAccount(ACCOUNT_ID);

        verify(accountRepository).closeIfEmpty(ACCOUNT_ID);
        verify(events).publishEvent(any(AccountsChangedEvent.class));
    }

    @Test
    void anAccountHoldingMoneyCannotBeClosed() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account("125.0000")));
        when(accountRepository.closeIfEmpty(ACCOUNT_ID)).thenReturn(0);

        assertThatThrownBy(() -> accountService.closeAccount(ACCOUNT_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("still holds a balance")
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /**
     * Nothing changed, so nothing may be evicted — a spurious eviction is only a free cache miss,
     * but publishing one here would imply the close happened.
     */
    @Test
    void aRefusedCloseChangesNothingAndPublishesNothing() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account("125.0000")));
        when(accountRepository.closeIfEmpty(ACCOUNT_ID)).thenReturn(0);

        assertThatThrownBy(() -> accountService.closeAccount(ACCOUNT_ID))
                .isInstanceOf(AppException.class);

        verify(events, never()).publishEvent(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * The decisive check is the database's, not the entity's. Here the row loaded a moment ago reads
     * as empty, but a credit committed before the close ran — so the conditional UPDATE matches
     * nothing and the close is refused. Reading the balance in Java and then writing the status
     * would have closed the account and orphaned the money that had just arrived.
     */
    @Test
    void aCreditLandingAfterTheReadStillBlocksTheClose() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account("0.0000")));
        when(accountRepository.closeIfEmpty(ACCOUNT_ID)).thenReturn(0);

        assertThatThrownBy(() -> accountService.closeAccount(ACCOUNT_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("still holds a balance");

        verify(events, never()).publishEvent(any());
    }

    @Test
    void closingAnUnknownAccountIsNotFound() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.closeAccount(ACCOUNT_ID))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(accountRepository, never()).closeIfEmpty(any());
    }
}
