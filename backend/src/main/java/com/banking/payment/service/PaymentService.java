package com.banking.payment.service;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.repository.AccountRepository;
import com.banking.common.exception.AppException;
import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.dto.TransferRequest;
import com.banking.payment.entity.Transaction;
import com.banking.payment.entity.TransactionStatus;
import com.banking.payment.entity.TransactionType;
import com.banking.payment.event.PaymentEvent;
import com.banking.payment.repository.TransactionRepository;
import com.banking.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PAYMENT_TOPIC = "payment.events";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @Transactional
    public TransactionResponse transfer(TransferRequest request) {
        User sender = currentUser();

        Account from = accountRepository.findById(request.fromAccountId())
                .orElseThrow(() -> new AppException("Source account not found", HttpStatus.NOT_FOUND));
        Account to = accountRepository.findById(request.toAccountId())
                .orElseThrow(() -> new AppException("Destination account not found", HttpStatus.NOT_FOUND));

        if (!from.getUser().getId().equals(sender.getId())) {
            throw new AppException("Source account not found", HttpStatus.NOT_FOUND);
        }
        if (from.getId().equals(to.getId())) {
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

        kafkaTemplate.send(PAYMENT_TOPIC, tx.getId().toString(),
                new PaymentEvent(
                        tx.getId(),
                        from.getId(),
                        to.getId(),
                        from.getUser().getId(),
                        to.getUser().getId(),
                        request.amount(),
                        TransactionType.TRANSFER,
                        Instant.now()
                ));

        return TransactionResponse.from(tx);
    }

    public List<TransactionResponse> myTransactions() {
        return transactionRepository.findByUserId(currentUser().getId()).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    public List<TransactionResponse> transactionsByAccount(UUID accountId) {
        // Caller must verify ownership before calling
        return transactionRepository.findByAccountId(accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
