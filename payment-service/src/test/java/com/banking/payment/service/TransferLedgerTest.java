package com.banking.payment.service;

import com.banking.events.TransferExecutionResult;
import com.banking.payment.dto.TransferRequest;
import com.banking.payment.entity.OutboxEvent;
import com.banking.payment.entity.OutboxStatus;
import com.banking.payment.entity.Transaction;
import com.banking.payment.entity.TransactionStatus;
import com.banking.payment.entity.TransactionType;
import com.banking.payment.event.OutboxTriggerEvent;
import com.banking.payment.repository.OutboxEventRepository;
import com.banking.payment.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The individually committed steps of the saga. What matters here is that an intent carries only
 * what the caller supplied, that settlement fills in what account-service actually did, and that
 * settling twice does not notify the user twice.
 */
@ExtendWith(MockitoExtension.class)
class TransferLedgerTest {

    @Mock TransactionRepository transactionRepository;
    @Mock OutboxEventRepository outboxRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    /** Real mapper: the outbox payload is the contract with notification-service. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule()).build();

    private TransferLedger ledger() {
        return new TransferLedger(transactionRepository, outboxRepository, objectMapper, eventPublisher);
    }

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID FROM_ACCOUNT = UUID.randomUUID();
    private static final UUID TO_ACCOUNT = UUID.randomUUID();
    private static final UUID INTENT_ID = UUID.randomUUID();

    private TransferRequest request(UUID key) {
        return new TransferRequest(FROM_ACCOUNT, "000000000002",
                new BigDecimal("25.0000"), "rent", key);
    }

    private TransferExecutionResult executionResult() {
        return new TransferExecutionResult("000000000001", "000000000002",
                new BigDecimal("75.0000"), new BigDecimal("125.0000"),
                CALLER, RECIPIENT, TO_ACCOUNT);
    }

    private Transaction pending(UUID key) {
        return Transaction.builder()
                .id(INTENT_ID).idempotencyKey(key)
                .fromAccountId(FROM_ACCOUNT).toAccountNumber("000000000002")
                .fromUserId(CALLER).amount(new BigDecimal("25.0000"))
                .type(TransactionType.TRANSFER).status(TransactionStatus.PENDING)
                .build();
    }

    /**
     * The intent records the request, nothing more. The destination account id and the recipient's
     * user id are account-service's to determine — inventing them here would let a caller write a
     * ledger row naming an account they do not know exists.
     */
    @Test
    void intentIsPendingAndCarriesOnlyWhatTheCallerSupplied() {
        UUID key = UUID.randomUUID();
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        ledger().openIntent(request(key), CALLER);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction intent = captor.getValue();

        assertThat(intent.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(intent.getIdempotencyKey()).isEqualTo(key);
        assertThat(intent.getFromUserId()).isEqualTo(CALLER);
        assertThat(intent.getToAccountId()).as("not knowable before execution").isNull();
        assertThat(intent.getToUserId()).as("not knowable before execution").isNull();
    }

    /** No event may be emitted for a transfer that has not been recorded as complete. */
    @Test
    void openingAnIntentEmitsNoOutboxEvent() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        ledger().openIntent(request(UUID.randomUUID()), CALLER);

        verify(outboxRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(OutboxTriggerEvent.class));
    }

    @Test
    void settlementFillsInWhatAccountServiceActuallyDid() {
        Transaction intent = pending(UUID.randomUUID());
        when(transactionRepository.findById(INTENT_ID)).thenReturn(Optional.of(intent));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        Transaction settled = ledger().settleCompleted(INTENT_ID, executionResult());

        assertThat(settled.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(settled.getToAccountId()).isEqualTo(TO_ACCOUNT);
        assertThat(settled.getToUserId()).isEqualTo(RECIPIENT);
        assertThat(settled.getFromAccountNumber()).isEqualTo("000000000001");
    }

    @Test
    void settlementWritesTheOutboxEventCarryingBothBalances() throws Exception {
        Transaction intent = pending(UUID.randomUUID());
        when(transactionRepository.findById(INTENT_ID)).thenReturn(Optional.of(intent));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        ledger().settleCompleted(INTENT_ID, executionResult());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();

        assertThat(event.getTopic()).isEqualTo("payment.events");
        assertThat(event.getAggregateId()).isEqualTo(INTENT_ID.toString());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(new BigDecimal(payload.get("fromAccountBalance").asText())).isEqualByComparingTo("75.0000");
        assertThat(new BigDecimal(payload.get("toAccountBalance").asText())).isEqualByComparingTo("125.0000");
        assertThat(payload.get("toUserId").asText()).isEqualTo(RECIPIENT.toString());
        verify(eventPublisher).publishEvent(any(OutboxTriggerEvent.class));
    }

    /**
     * The client's retry and the recovery poller can both settle the same intent. Without this
     * guard the second one writes another outbox event and the user is notified twice for one
     * transfer — the outbox is at-least-once, so duplicates here become duplicates downstream.
     */
    @Test
    void settlingAnAlreadySettledIntentIsANoOp() {
        Transaction alreadyDone = pending(UUID.randomUUID());
        alreadyDone.setStatus(TransactionStatus.COMPLETED);
        when(transactionRepository.findById(INTENT_ID)).thenReturn(Optional.of(alreadyDone));

        ledger().settleCompleted(INTENT_ID, executionResult());

        verify(outboxRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(OutboxTriggerEvent.class));
    }

    /** A failed transfer has nothing to announce: no money moved and the sender already has the error. */
    @Test
    void failureWritesNoOutboxEvent() {
        Transaction intent = pending(UUID.randomUUID());
        when(transactionRepository.findById(INTENT_ID)).thenReturn(Optional.of(intent));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        Transaction failed = ledger().settleFailed(INTENT_ID, "Insufficient balance");

        assertThat(failed.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(failed.getDescription()).contains("Insufficient balance");
        verify(outboxRepository, never()).save(any());
    }

    /** description is a 255-char column; a long reason must not break the settlement write. */
    @Test
    void failureReasonIsTruncatedToFitTheColumn() {
        Transaction intent = pending(UUID.randomUUID());
        when(transactionRepository.findById(INTENT_ID)).thenReturn(Optional.of(intent));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        Transaction failed = ledger().settleFailed(INTENT_ID, "x".repeat(500));

        assertThat(failed.getDescription()).hasSizeLessThanOrEqualTo(255);
    }
}
