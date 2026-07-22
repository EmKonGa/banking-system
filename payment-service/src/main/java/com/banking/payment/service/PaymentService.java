package com.banking.payment.service;

import com.banking.common.exception.AppException;
import com.banking.common.security.JwtPrincipal;
import com.banking.events.PaymentEvent;
import com.banking.events.TransferExecutionRequest;
import com.banking.events.TransferExecutionResult;
import com.banking.payment.client.AccountServiceClient;
import com.banking.payment.dto.TransactionResponse;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PAYMENT_TOPIC = "payment.events";

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public TransactionResponse transfer(TransferRequest request) {
        JwtPrincipal principal = currentPrincipal();
        // Prefer client-provided key so retries hit the idempotency cache.
        // Server-generated fallback is not retry-safe.
        UUID idempotencyKey = request.idempotencyKey() != null ? request.idempotencyKey() : UUID.randomUUID();

        TransferExecutionResult result = accountServiceClient.executeTransfer(
                new TransferExecutionRequest(
                        request.fromAccountId(),
                        request.toAccountNumber(),
                        request.amount(),
                        idempotencyKey,
                        principal.id()
                )
        );

        Transaction tx = Transaction.builder()
                .fromAccountId(request.fromAccountId())
                .toAccountId(result.toAccountId())
                .fromAccountNumber(result.fromAccountNumber())
                .toAccountNumber(result.toAccountNumber())
                .fromUserId(result.fromUserId())
                .toUserId(result.toUserId())
                .amount(request.amount())
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .description(request.description())
                .build();
        transactionRepository.save(tx);

        PaymentEvent paymentEvent = new PaymentEvent(
                tx.getId(),
                request.fromAccountId(),
                result.toAccountId(),
                result.fromAccountNumber(),
                result.toAccountNumber(),
                result.fromUserId(),
                result.toUserId(),
                request.amount(),
                result.fromBalance(),
                result.toBalance(),
                TransactionType.TRANSFER.name(),
                Instant.now()
        );

        try {
            String payload = objectMapper.writeValueAsString(paymentEvent);
            outboxRepository.save(OutboxEvent.of(PAYMENT_TOPIC, tx.getId().toString(), payload));
        } catch (JsonProcessingException e) {
            throw new AppException("Failed to serialize payment event", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Fire after commit so the poller sees the committed outbox row immediately.
        eventPublisher.publishEvent(new OutboxTriggerEvent());

        return TransactionResponse.from(tx);
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
