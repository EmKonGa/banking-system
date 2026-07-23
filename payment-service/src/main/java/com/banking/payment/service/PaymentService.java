package com.banking.payment.service;

import com.banking.common.exception.AppException;
import com.banking.common.security.JwtPrincipal;
import com.banking.events.TransferExecutionRequest;
import com.banking.events.TransferExecutionResult;
import com.banking.payment.client.AccountServiceClient;
import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.dto.TransferRequest;
import com.banking.payment.entity.Transaction;
import com.banking.payment.repository.TransactionRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final TransferLedger ledger;

    /**
     * Deliberately <strong>not</strong> {@code @Transactional} — the money commits in
     * account-service's database and the ledger row commits here, so one annotation cannot span
     * both. Wrapping this method only created the illusion that it did.
     *
     * <p>Intent first, then the money, then settlement. A failure in between leaves the intent
     * PENDING for {@link TransferRecoveryPoller}, which is the point: the window is never silent.
     */
    public TransactionResponse transfer(TransferRequest request) {
        JwtPrincipal principal = currentPrincipal();
        UUID idempotencyKey = request.idempotencyKey();
        if (idempotencyKey == null) {
            throw new AppException("idempotencyKey is required", HttpStatus.BAD_REQUEST);
        }

        Transaction intent;
        try {
            intent = ledger.openIntent(request, principal.id());
        } catch (DataIntegrityViolationException e) {
            // Key already used. account-service dedupes the money; this stops a second ledger row.
            return replayOf(idempotencyKey);
        }

        TransferExecutionResult result;
        try {
            result = accountServiceClient.executeTransfer(new TransferExecutionRequest(
                    request.fromAccountId(),
                    request.toAccountNumber(),
                    request.amount(),
                    idempotencyKey,
                    principal.id()));
        } catch (FeignException e) {
            if (isDefiniteRejection(e)) {
                AppException rejection = toAppException(e);
                ledger.settleFailed(intent.getId(), rejection.getMessage());
                throw rejection;
            }
            throw indeterminate(intent, e);
        } catch (Exception e) {
            // Timeout, connection failure, open breaker — outcome unknown, same as a 5xx.
            throw indeterminate(intent, e);
        }

        return TransactionResponse.from(ledger.settleCompleted(intent.getId(), result));
    }

    /**
     * A 4xx means account-service evaluated the request and refused it — insufficient funds, a
     * frozen account, an unknown destination. No money moved, and retrying the same request will
     * not change that. Anything else is an infrastructure failure whose outcome is unknown.
     */
    private boolean isDefiniteRejection(FeignException e) {
        return e.status() >= 400 && e.status() < 500;
    }

    /**
     * Surfaces account-service's own status and message. Without this the caller sees a bare
     * FeignException, which no handler maps — so "insufficient balance" reached the user as a 500.
     */
    private AppException toAppException(FeignException e) {
        HttpStatus status = HttpStatus.resolve(e.status());
        String message = e.responseBody()
                .map(PaymentService::readUtf8)
                .map(PaymentService::extractMessage)
                .orElse("Transfer rejected");
        return new AppException(message, status != null ? status : HttpStatus.BAD_REQUEST);
    }

    /** Reads without assuming the buffer has an accessible backing array. */
    private static String readUtf8(java.nio.ByteBuffer body) {
        java.nio.ByteBuffer copy = body.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Pulls "message" out of the shared ErrorResponse shape without a full parse. */
    private static String extractMessage(String body) {
        int at = body.indexOf("\"message\"");
        if (at < 0) return "Transfer rejected";
        int open = body.indexOf('"', body.indexOf(':', at) + 1);
        int close = body.indexOf('"', open + 1);
        return open > 0 && close > open ? body.substring(open + 1, close) : "Transfer rejected";
    }

    /**
     * Leaves the intent PENDING on purpose. Settling it FAILED here would be a guess, and the wrong
     * guess writes off a transfer whose money actually moved.
     */
    private AppException indeterminate(Transaction intent, Exception cause) {
        log.error("[SAGA] intent {} left PENDING — outcome unknown: {}", intent.getId(), cause.toString());
        return new AppException(
                "Transfer is being processed. Check your transaction history before retrying.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    private TransactionResponse replayOf(UUID idempotencyKey) {
        Transaction existing = ledger.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new AppException("Transfer conflict", HttpStatus.CONFLICT));
        log.info("[SAGA] duplicate submit for key {} → returning existing {} transfer",
                idempotencyKey, existing.getStatus());
        return TransactionResponse.from(existing);
    }

    @Transactional(readOnly = true)
    public Slice<TransactionResponse> myTransactions(Pageable pageable) {
        return transactionRepository.findByUserId(currentPrincipal().id(), pageable)
                .map(TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> transactionsByAccount(UUID accountId, Pageable pageable) {
        return transactionRepository.findByAccountId(accountId, pageable)
                .map(TransactionResponse::from);
    }

    private JwtPrincipal currentPrincipal() {
        return (JwtPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
