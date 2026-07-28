package com.banking.account.service;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.entity.AccountTransferLog;
import com.banking.account.entity.MovementType;
import com.banking.account.event.AccountsChangedEvent;
import com.banking.account.repository.AccountRepository;
import com.banking.account.repository.AccountTransferLogRepository;
import com.banking.common.exception.AppException;
import com.banking.events.DepositExecutionRequest;
import com.banking.events.TransferExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credits an account from outside the system, on behalf of payment-service.
 *
 * <p>This used to live on {@link AccountService} as a public operation that mutated a balance and
 * told no one. It moved here when deposits were brought into the ledger: money entering the system
 * has to leave the same evidence a transfer does, or the reconciliation invariant
 * {@code sum(debits) == sum(credits) == sum(balance deltas)} reports every deposit as money created
 * from nothing.
 */
@Service
@RequiredArgsConstructor
public class InternalDepositService {

    private final AccountRepository accountRepository;
    private final AccountTransferLogRepository transferLogRepository;
    private final ApplicationEventPublisher events;

    /**
     * The credit and the log row commit together — that co-commit is the whole contract, because
     * the recovery poller reads the absence of a log row as proof that no money moved.
     */
    @Transactional
    public TransferExecutionResult execute(DepositExecutionRequest request) {
        Account to = accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new AppException("Account not found", HttpStatus.NOT_FOUND));

        if (to.getStatus() != AccountStatus.ACTIVE) {
            throw new AppException("Account is not active", HttpStatus.BAD_REQUEST);
        }

        int credited = accountRepository.addBalance(to.getId(), request.amount());
        if (credited == 0) {
            // Lost the race with a freeze or a close between the status check and the update.
            throw new AppException("Account is not active", HttpStatus.BAD_REQUEST);
        }

        // Reload for the committed balance: addBalance is a bulk update, so the entity above is
        // stale and its persistence context was cleared.
        to = accountRepository.findById(to.getId()).orElseThrow();

        // PK on idempotency_key: a concurrent request with the same key already committed, so this
        // insert throws DataIntegrityViolationException and the caller re-reads the winning row.
        transferLogRepository.save(AccountTransferLog.builder()
                .idempotencyKey(request.idempotencyKey())
                .type(MovementType.DEPOSIT)
                .toAccountId(to.getId())
                .toUserId(to.getUserId())
                .toAccountNumber(to.getAccountNumber())
                .toBalance(to.getBalance())
                .build());

        events.publishEvent(AccountsChangedEvent.of(to.getId(), to.getUserId()));

        // Shares TransferExecutionResult with the transfer path so payment-service settles both
        // through one code path. The source-side fields are null: a deposit has no source.
        return new TransferExecutionResult(null, to.getAccountNumber(), null, to.getBalance(),
                null, to.getUserId(), to.getId());
    }
}
