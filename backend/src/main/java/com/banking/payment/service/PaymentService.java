package com.banking.payment.service;

import com.banking.account.dto.AccountResponse;
import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.repository.AccountRepository;
import com.banking.common.exception.AppException;
import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.dto.TransferRequest;
import com.banking.payment.entity.OutboxEvent;
import com.banking.payment.entity.Transaction;
import com.banking.payment.entity.TransactionStatus;
import com.banking.payment.entity.TransactionType;
import com.banking.payment.event.PaymentCompletedEvent;
import com.banking.payment.event.PaymentEvent;
import com.banking.payment.repository.OutboxEventRepository;
import com.banking.payment.repository.TransactionRepository;
import com.banking.user.entity.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PAYMENT_TOPIC = "payment.events";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public TransactionResponse transfer(TransferRequest request) {
        User sender = currentUser();

        Account from = accountRepository.findById(request.fromAccountId())
                .orElseThrow(() -> new AppException("Source account not found", HttpStatus.NOT_FOUND));
        Account to = accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new AppException("Destination account not found", HttpStatus.NOT_FOUND));

        if (!from.getUser().getId().equals(sender.getId())) {
            throw new AppException("Source account not found", HttpStatus.NOT_FOUND);
        }
        if (from.getAccountNumber().equals(to.getAccountNumber())) {
            throw new AppException("Cannot transfer to the same account", HttpStatus.BAD_REQUEST);
        }
        if (from.getStatus() != AccountStatus.ACTIVE) {
            throw new AppException("Source account is not active", HttpStatus.BAD_REQUEST);
        }
        if (to.getStatus() != AccountStatus.ACTIVE) {
            throw new AppException("Destination account is not active", HttpStatus.BAD_REQUEST);
        }
        if (from.getBalance().compareTo(request.amount()) < 0) {
            throw new AppException("Insufficient balance", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        from.setBalance(from.getBalance().subtract(request.amount()));
        to.setBalance(to.getBalance().add(request.amount()));

        Transaction tx = Transaction.builder()
                .fromAccount(from)
                .toAccount(to)
                .amount(request.amount())
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .description(request.description())
                .build();
        transactionRepository.save(tx);

        // Build snapshots while still inside the transaction
        PaymentEvent paymentEvent = new PaymentEvent(
                tx.getId(),
                from.getId(),
                to.getId(),
                from.getAccountNumber(),
                to.getAccountNumber(),
                from.getUser().getId(),
                to.getUser().getId(),
                request.amount(),
                TransactionType.TRANSFER,
                Instant.now()
        );

        // Persist outbox event atomically with the transfer — guarantees at-least-once Kafka delivery
        try {
            String payload = objectMapper.writeValueAsString(paymentEvent);
            outboxRepository.save(OutboxEvent.of(PAYMENT_TOPIC, tx.getId().toString(), payload));
        } catch (JsonProcessingException e) {
            throw new AppException("Failed to serialize payment event", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Fire Spring event for WebSocket balance/transaction push (best-effort, after commit)
        eventPublisher.publishEvent(new PaymentCompletedEvent(
                from.getUser().getId().toString(),
                to.getUser().getId().toString(),
                AccountResponse.from(from),
                AccountResponse.from(to),
                TransactionResponse.from(tx)
        ));

        return TransactionResponse.from(tx);
    }

    public List<TransactionResponse> myTransactions() {
        return transactionRepository.findByUserId(currentUser().getId()).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    public List<TransactionResponse> transactionsByAccount(UUID accountId) {
        return transactionRepository.findByAccountId(accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
