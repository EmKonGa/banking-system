package com.banking.payment.service;

import com.banking.common.exception.AppException;
import com.banking.events.PaymentEvent;
import com.banking.events.TransferExecutionResult;
import com.banking.payment.dto.TransferRequest;
import com.banking.payment.entity.OutboxEvent;
import com.banking.payment.entity.Transaction;
import com.banking.payment.entity.TransactionStatus;
import com.banking.payment.entity.TransactionType;
import com.banking.payment.event.OutboxTriggerEvent;
import com.banking.payment.repository.OutboxEventRepository;
import com.banking.payment.repository.TransactionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The individually committed steps of a transfer.
 *
 * <p>This is a separate bean because Spring only applies {@code @Transactional} through a proxy:
 * keeping these on {@link PaymentService} and calling them from its own {@code transfer} would run
 * them in one transaction and restore the dual-write bug they exist to fix.
 *
 * <p>Propagation is {@code REQUIRED}, not {@code REQUIRES_NEW}, and the difference is a silent hang
 * rather than an error. {@code REQUIRES_NEW} takes a different pooled connection and blocks
 * updating a row that {@link TransferRecoveryPoller}'s transaction holds under
 * {@code FOR UPDATE SKIP LOCKED}, while that poller waits for the call to return. Postgres cannot
 * see it as a deadlock — the outer side is blocked on application logic, not a lock — so it hangs
 * until a timeout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferLedger {

    private static final String PAYMENT_TOPIC = "payment.events";

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Records what the caller asked for, before any money moves. Only the request-side fields are
     * known here — the destination account id, both balances and the recipient's user id come back
     * from account-service and are filled in at settlement.
     */
    @Transactional
    public Transaction openIntent(TransferRequest request, UUID fromUserId) {
        return transactionRepository.save(Transaction.builder()
                .idempotencyKey(request.idempotencyKey())
                .fromAccountId(request.fromAccountId())
                .toAccountNumber(request.toAccountNumber())
                .fromUserId(fromUserId)
                .amount(request.amount())
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .description(request.description())
                .build());
    }

    /**
     * Completes the intent with what account-service actually did, and writes the outbox event in
     * the same transaction — the event must not become visible for a transfer whose ledger row
     * failed to commit.
     */
    @Transactional
    public Transaction settleCompleted(UUID transactionId, TransferExecutionResult result) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new AppException("Transfer intent not found", HttpStatus.INTERNAL_SERVER_ERROR));

        if (tx.getStatus() != TransactionStatus.PENDING) {
            // A client retry and the recovery poller can both land here; a second pass would write
            // another outbox event and notify the user twice.
            log.debug("[SAGA] intent {} already {}", transactionId, tx.getStatus());
            return tx;
        }

        tx.setToAccountId(result.toAccountId());
        tx.setFromAccountNumber(result.fromAccountNumber());
        tx.setToAccountNumber(result.toAccountNumber());
        tx.setToUserId(result.toUserId());
        tx.setStatus(TransactionStatus.COMPLETED);
        transactionRepository.save(tx);

        writeOutboxEvent(tx, result);
        eventPublisher.publishEvent(new OutboxTriggerEvent());
        return tx;
    }

    /**
     * Marks an intent that definitively did not move money. Writes no outbox event: there is
     * nothing for the recipient to be notified about, and the sender already has the error.
     */
    @Transactional
    public Transaction settleFailed(UUID transactionId, String reason) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new AppException("Transfer intent not found", HttpStatus.INTERNAL_SERVER_ERROR));

        if (tx.getStatus() != TransactionStatus.PENDING) {
            return tx;
        }

        tx.setStatus(TransactionStatus.FAILED);
        tx.setDescription(truncate(tx.getDescription(), reason));
        log.warn("[SAGA] intent {} settled FAILED: {}", transactionId, reason);
        return transactionRepository.save(tx);
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> findByIdempotencyKey(UUID idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey);
    }

    private void writeOutboxEvent(Transaction tx, TransferExecutionResult result) {
        PaymentEvent event = new PaymentEvent(
                tx.getId(),
                tx.getFromAccountId(),
                result.toAccountId(),
                result.fromAccountNumber(),
                result.toAccountNumber(),
                tx.getFromUserId(),
                result.toUserId(),
                tx.getAmount(),
                result.fromBalance(),
                result.toBalance(),
                TransactionType.TRANSFER.name(),
                Instant.now()
        );
        try {
            outboxRepository.save(OutboxEvent.of(
                    PAYMENT_TOPIC, tx.getId().toString(), objectMapper.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            throw new AppException("Failed to serialize payment event", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Keeps the original description and appends why it failed, within the column's 255 chars. */
    private String truncate(String description, String reason) {
        String combined = description == null || description.isBlank()
                ? "FAILED: " + reason
                : description + " — FAILED: " + reason;
        return combined.length() > 255 ? combined.substring(0, 255) : combined;
    }
}
