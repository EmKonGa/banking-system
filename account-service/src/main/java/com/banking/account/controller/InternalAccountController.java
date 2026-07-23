package com.banking.account.controller;

import com.banking.account.entity.AccountTransferLog;
import com.banking.account.repository.AccountTransferLogRepository;
import com.banking.account.service.InternalTransferService;
import com.banking.common.exception.AppException;
import com.banking.events.TransferExecutionRequest;
import com.banking.events.TransferExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/accounts")
@RequiredArgsConstructor
public class InternalAccountController {

    private final InternalTransferService transferService;
    private final AccountTransferLogRepository transferLogRepository;

    @GetMapping("/transfers/{idempotencyKey}")
    public TransferExecutionResult findTransfer(@PathVariable UUID idempotencyKey) {
        return transferLogRepository.findById(idempotencyKey)
                .map(this::toResult)
                .orElseThrow(() -> new AppException("No transfer for that key", HttpStatus.NOT_FOUND));
    }

    @PostMapping("/execute-transfer")
    public TransferExecutionResult executeTransfer(@RequestBody TransferExecutionRequest request) {
        // Fast path: retry after a completed transfer returns the original result immediately.
        return transferLogRepository.findById(request.idempotencyKey())
                .map(this::toResult)
                .orElseGet(() -> doTransferWithIdempotency(request));
    }

    private TransferExecutionResult doTransferWithIdempotency(TransferExecutionRequest request) {
        try {
            return transferService.execute(request);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request with the same idempotency key already committed.
            // The service transaction rolled back cleanly; return the winning result.
            return transferLogRepository.findById(request.idempotencyKey())
                    .map(this::toResult)
                    .orElseThrow(() -> new AppException("Transfer conflict", HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    private TransferExecutionResult toResult(AccountTransferLog log) {
        return new TransferExecutionResult(
                log.getFromAccountNumber(), log.getToAccountNumber(),
                log.getFromBalance(), log.getToBalance(),
                log.getFromUserId(), log.getToUserId(),
                log.getToAccountId());
    }
}
