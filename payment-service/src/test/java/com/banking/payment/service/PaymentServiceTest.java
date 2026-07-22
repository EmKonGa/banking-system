package com.banking.payment.service;

import com.banking.common.security.JwtPrincipal;
import com.banking.events.TransferExecutionRequest;
import com.banking.events.TransferExecutionResult;
import com.banking.payment.client.AccountServiceClient;
import com.banking.payment.dto.TransactionResponse;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Guards the money path in payment-service: that a transfer's ledger row and its outbox event are
 * built from what account-service actually did rather than from what the client asked for, that a
 * failed transfer leaves no trace, and that the idempotency key a retry depends on is forwarded.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock OutboxEventRepository outboxRepository;
    @Mock AccountServiceClient accountServiceClient;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymentService paymentService;

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID FROM_ACCOUNT = UUID.randomUUID();
    private static final UUID TO_ACCOUNT = UUID.randomUUID();

    /** PaymentEvent carries an Instant, which a bare ObjectMapper cannot serialize. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @BeforeEach
    void setUp() {
        // Real ObjectMapper rather than a mock: the outbox payload is the contract with
        // notification-service, so serializing it for real is the point of the assertion.
        paymentService = new PaymentService(transactionRepository, outboxRepository,
                accountServiceClient, objectMapper, eventPublisher);
        authenticateAs(CALLER);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtPrincipal(userId, userId + "@example.com", "USER"), null, List.of()));
    }

    private TransferRequest request(UUID idempotencyKey) {
        return new TransferRequest(FROM_ACCOUNT, "000000000002",
                new BigDecimal("25.0000"), "rent", idempotencyKey);
    }

    private TransferExecutionResult executionResult() {
        return new TransferExecutionResult("000000000001", "000000000002",
                new BigDecimal("75.0000"), new BigDecimal("125.0000"),
                CALLER, RECIPIENT, TO_ACCOUNT);
    }

    /**
     * Transaction.id is {@code @GeneratedValue}, so with a mocked repository nothing would assign
     * it and {@code tx.getId()} would be null when the outbox payload is built. JPA assigns it to
     * the passed instance during persist; this reproduces that.
     */
    private void stubSaveAssigningId() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
    }

    /**
     * The client-supplied key is what makes a retried transfer idempotent — account-service
     * deduplicates on it. If the server replaced it, a client retry after a timeout would execute
     * the transfer a second time.
     */
    @Test
    void clientProvidedIdempotencyKeyIsForwardedUnchanged() {
        UUID clientKey = UUID.randomUUID();
        stubSaveAssigningId();
        when(accountServiceClient.executeTransfer(any())).thenReturn(executionResult());

        paymentService.transfer(request(clientKey));

        ArgumentCaptor<TransferExecutionRequest> captor =
                ArgumentCaptor.forClass(TransferExecutionRequest.class);
        verify(accountServiceClient).executeTransfer(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(clientKey);
        assertThat(captor.getValue().fromUserId()).isEqualTo(CALLER);
    }

    /** Absent key still yields one, so account-service's dedupe path always has something to key on. */
    @Test
    void absentIdempotencyKeyIsGeneratedPerCall() {
        stubSaveAssigningId();
        when(accountServiceClient.executeTransfer(any())).thenReturn(executionResult());

        paymentService.transfer(request(null));
        paymentService.transfer(request(null));

        ArgumentCaptor<TransferExecutionRequest> captor =
                ArgumentCaptor.forClass(TransferExecutionRequest.class);
        verify(accountServiceClient, org.mockito.Mockito.times(2)).executeTransfer(captor.capture());
        assertThat(captor.getAllValues()).extracting(TransferExecutionRequest::idempotencyKey)
                .doesNotContainNull().doesNotHaveDuplicates();
    }

    /**
     * The ledger row must reflect the executed transfer, not the request. Account numbers, the
     * destination account id and both user ids are only known to account-service — taking them
     * from the request would let a caller write a row naming someone else's account.
     */
    @Test
    void ledgerRowIsBuiltFromTheExecutionResultNotTheRequest() {
        stubSaveAssigningId();
        when(accountServiceClient.executeTransfer(any())).thenReturn(executionResult());

        TransactionResponse response = paymentService.transfer(request(UUID.randomUUID()));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();

        assertThat(saved.getFromAccountId()).isEqualTo(FROM_ACCOUNT);
        assertThat(saved.getToAccountId()).isEqualTo(TO_ACCOUNT);
        assertThat(saved.getFromUserId()).isEqualTo(CALLER);
        assertThat(saved.getToUserId()).isEqualTo(RECIPIENT);
        assertThat(saved.getAmount()).isEqualByComparingTo("25.0000");
        assertThat(saved.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.id()).isEqualTo(saved.getId());
    }

    /**
     * The outbox row is the only delivery guarantee for the notification. It has to carry both
     * post-transfer balances and be keyed by the transaction id, or notification-service cannot
     * describe what happened and the poller cannot correlate a retry.
     */
    @Test
    void outboxEventIsPendingAndCarriesBothBalances() throws Exception {
        stubSaveAssigningId();
        when(accountServiceClient.executeTransfer(any())).thenReturn(executionResult());

        TransactionResponse response = paymentService.transfer(request(UUID.randomUUID()));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();

        assertThat(event.getTopic()).isEqualTo("payment.events");
        assertThat(event.getAggregateId()).isEqualTo(response.id().toString());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("transactionId").asText()).isEqualTo(response.id().toString());
        assertThat(new BigDecimal(payload.get("fromAccountBalance").asText())).isEqualByComparingTo("75.0000");
        assertThat(new BigDecimal(payload.get("toAccountBalance").asText())).isEqualByComparingTo("125.0000");
        assertThat(payload.get("toUserId").asText()).isEqualTo(RECIPIENT.toString());
    }

    /**
     * The trigger tells OutboxPoller to look immediately instead of waiting for its next tick. It
     * must be published, or every transfer's notification waits out the poll interval.
     */
    @Test
    void outboxTriggerIsPublishedAfterAConsistentWrite() {
        stubSaveAssigningId();
        when(accountServiceClient.executeTransfer(any())).thenReturn(executionResult());

        paymentService.transfer(request(UUID.randomUUID()));

        verify(eventPublisher).publishEvent(any(OutboxTriggerEvent.class));
    }

    /**
     * If account-service never moved the money, nothing may be recorded. The write happens after
     * the Feign call for exactly this reason — a ledger row here without a balance change there
     * would be money invented on the reporting side.
     */
    @Test
    void failedExecutionWritesNeitherLedgerRowNorOutboxEvent() {
        when(accountServiceClient.executeTransfer(any()))
                .thenThrow(new IllegalStateException("account-service unavailable"));

        assertThatThrownBy(() -> paymentService.transfer(request(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(transactionRepository, outboxRepository);
        verify(eventPublisher, never()).publishEvent(any(OutboxTriggerEvent.class));
    }

    /**
     * The listing is scoped to the authenticated principal, never to a caller-supplied id — the
     * endpoint takes no user parameter precisely so one user cannot read another's ledger.
     */
    @Test
    void myTransactionsQueriesOnlyTheAuthenticatedUser() {
        Pageable pageable = PageRequest.of(0, 20);
        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID())
                .fromAccountId(FROM_ACCOUNT).toAccountId(TO_ACCOUNT)
                .toAccountNumber("000000000002")
                .fromUserId(CALLER).toUserId(RECIPIENT)
                .amount(new BigDecimal("25.0000"))
                .type(TransactionType.TRANSFER).status(TransactionStatus.COMPLETED)
                .build();
        when(transactionRepository.findByUserId(CALLER, pageable))
                .thenReturn(new SliceImpl<>(List.of(tx), pageable, false));

        Slice<TransactionResponse> result = paymentService.myTransactions(pageable);

        assertThat(result.getContent()).extracting(TransactionResponse::id).containsExactly(tx.getId());
        assertThat(result.hasNext()).isFalse();
        verify(transactionRepository).findByUserId(CALLER, pageable);
    }
}
