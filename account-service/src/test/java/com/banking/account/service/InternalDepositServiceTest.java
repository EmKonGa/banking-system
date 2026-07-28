package com.banking.account.service;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.entity.AccountTransferLog;
import com.banking.account.entity.AccountType;
import com.banking.account.entity.MovementType;
import com.banking.account.repository.AccountRepository;
import com.banking.account.repository.AccountTransferLogRepository;
import com.banking.common.exception.AppException;
import com.banking.events.DepositExecutionRequest;
import com.banking.events.TransferExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The account-service half of a deposit. Two things matter here: the credit is a single atomic
 * UPDATE rather than a read-modify-write, and it commits together with the log row that lets
 * payment-service's recovery poller find out it happened.
 */
@ExtendWith(MockitoExtension.class)
class InternalDepositServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock AccountTransferLogRepository transferLogRepository;
    @Mock ApplicationEventPublisher events;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private InternalDepositService service() {
        return new InternalDepositService(accountRepository, transferLogRepository, events);
    }

    private Account account(AccountStatus status, String balance) {
        return Account.builder()
                .id(ACCOUNT_ID).userId(OWNER).accountNumber("000000000002")
                .balance(new BigDecimal(balance)).type(AccountType.SAVINGS).status(status)
                .build();
    }

    private DepositExecutionRequest request() {
        return new DepositExecutionRequest("000000000002", new BigDecimal("500.0000"), UUID.randomUUID());
    }

    /**
     * The old implementation read the balance into memory, added to it and saved the entity back.
     * Two concurrent deposits both read the same starting balance and the second save overwrites the
     * first — one deposit vanishes while its ledger row remains. An atomic UPDATE cannot lose one.
     */
    @Test
    void creditsWithAnAtomicUpdateRatherThanAReadModifyWrite() {
        when(accountRepository.findByAccountNumber("000000000002"))
                .thenReturn(Optional.of(account(AccountStatus.ACTIVE, "125.0000")));
        when(accountRepository.addBalance(ACCOUNT_ID, new BigDecimal("500.0000"))).thenReturn(1);
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(account(AccountStatus.ACTIVE, "625.0000")));

        service().execute(request());

        verify(accountRepository).addBalance(ACCOUNT_ID, new BigDecimal("500.0000"));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * The log row is what makes "did money move for this key?" answerable. Its absence is treated as
     * conclusive by the recovery poller, so it has to be written in the same transaction as the
     * credit — never conditionally, never later.
     */
    @Test
    void writesADepositLogRowWithNoSourceSide() {
        when(accountRepository.findByAccountNumber("000000000002"))
                .thenReturn(Optional.of(account(AccountStatus.ACTIVE, "125.0000")));
        when(accountRepository.addBalance(any(), any())).thenReturn(1);
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(account(AccountStatus.ACTIVE, "625.0000")));

        DepositExecutionRequest request = request();
        service().execute(request);

        ArgumentCaptor<AccountTransferLog> captor = ArgumentCaptor.forClass(AccountTransferLog.class);
        verify(transferLogRepository).save(captor.capture());
        AccountTransferLog log = captor.getValue();

        assertThat(log.getType()).isEqualTo(MovementType.DEPOSIT);
        assertThat(log.getIdempotencyKey()).isEqualTo(request.idempotencyKey());
        assertThat(log.getToAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(log.getToBalance()).isEqualByComparingTo("625.0000");
        assertThat(log.getFromAccountId()).isNull();
        assertThat(log.getFromUserId()).isNull();
        assertThat(log.getFromBalance()).isNull();
    }

    /** The result feeds settlement in payment-service, so the committed balance has to be the new one. */
    @Test
    void reportsTheCommittedBalanceNotTheOneReadBeforeTheUpdate() {
        when(accountRepository.findByAccountNumber("000000000002"))
                .thenReturn(Optional.of(account(AccountStatus.ACTIVE, "125.0000")));
        when(accountRepository.addBalance(any(), any())).thenReturn(1);
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(account(AccountStatus.ACTIVE, "625.0000")));

        TransferExecutionResult result = service().execute(request());

        assertThat(result.toBalance()).isEqualByComparingTo("625.0000");
        assertThat(result.toAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.toUserId()).isEqualTo(OWNER);
        assertThat(result.fromAccountNumber()).isNull();
        assertThat(result.fromBalance()).isNull();
    }

    @Test
    void refusesToCreditAFrozenAccount() {
        when(accountRepository.findByAccountNumber("000000000002"))
                .thenReturn(Optional.of(account(AccountStatus.FROZEN, "125.0000")));

        assertThatThrownBy(() -> service().execute(request()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(accountRepository, never()).addBalance(any(), any());
        verify(transferLogRepository, never()).save(any());
    }

    /**
     * addBalance carries its own {@code status = 'ACTIVE'} predicate, so a freeze landing between
     * the check above and the update makes it a no-op. Zero rows updated means no money moved, and
     * the log row must not be written for it.
     */
    @Test
    void aFreezeRacingTheUpdateLeavesNoLogRow() {
        when(accountRepository.findByAccountNumber("000000000002"))
                .thenReturn(Optional.of(account(AccountStatus.ACTIVE, "125.0000")));
        when(accountRepository.addBalance(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().execute(request()))
                .isInstanceOf(AppException.class);

        verify(transferLogRepository, never()).save(any());
    }

    @Test
    void unknownAccountNumberIsNotFound() {
        when(accountRepository.findByAccountNumber("000000000002")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().execute(request()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
