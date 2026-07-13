package com.banking.account.service;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.entity.AccountTransferLog;
import com.banking.account.repository.AccountRepository;
import com.banking.account.repository.AccountTransferLogRepository;
import com.banking.common.exception.AppException;
import com.banking.events.TransferExecutionRequest;
import com.banking.events.TransferExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InternalTransferService {

    private final AccountRepository accountRepository;
    private final AccountTransferLogRepository transferLogRepository;

    @Transactional
    public TransferExecutionResult execute(TransferExecutionRequest request) {
        Account from = accountRepository.findById(request.fromAccountId())
                .orElseThrow(() -> new AppException("Source account not found", HttpStatus.NOT_FOUND));

        if (!from.getUserId().equals(request.fromUserId())) {
            throw new AppException("Source account not found", HttpStatus.NOT_FOUND);
        }
        if (from.getStatus() != AccountStatus.ACTIVE) {
            throw new AppException("Source account is not active", HttpStatus.BAD_REQUEST);
        }

        Account to = accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new AppException("Destination account not found", HttpStatus.NOT_FOUND));

        if (from.getId().equals(to.getId())) {
            throw new AppException("Cannot transfer to the same account", HttpStatus.BAD_REQUEST);
        }
        if (to.getStatus() != AccountStatus.ACTIVE) {
            throw new AppException("Destination account is not active", HttpStatus.BAD_REQUEST);
        }

        // Atomic debit: WHERE balance >= :amount makes the balance check and deduction one DB
        // operation — concurrent transfers cannot both pass on the same account.
        int debited = accountRepository.deductBalance(from.getId(), request.amount());
        if (debited == 0) {
            throw new AppException("Insufficient balance", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        accountRepository.addBalance(to.getId(), request.amount());

        // Reload to get committed balances after the bulk updates.
        from = accountRepository.findById(from.getId()).orElseThrow();
        to = accountRepository.findById(to.getId()).orElseThrow();

        // PK on idempotency_key: if a concurrent request with the same key already committed,
        // this insert throws DataIntegrityViolationException → transaction rolls back → caller
        // catches and re-reads the committed result.
        transferLogRepository.save(AccountTransferLog.builder()
                .idempotencyKey(request.idempotencyKey())
                .fromAccountId(from.getId())
                .toAccountId(to.getId())
                .fromUserId(from.getUserId())
                .toUserId(to.getUserId())
                .fromAccountNumber(from.getAccountNumber())
                .toAccountNumber(to.getAccountNumber())
                .fromBalance(from.getBalance())
                .toBalance(to.getBalance())
                .build());

        return new TransferExecutionResult(
                from.getAccountNumber(), to.getAccountNumber(),
                from.getBalance(), to.getBalance(),
                from.getUserId(), to.getUserId(),
                to.getId());
    }
}
