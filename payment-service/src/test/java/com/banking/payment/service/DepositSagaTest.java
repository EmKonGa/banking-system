package com.banking.payment.service;

import com.banking.common.exception.AppException;
import com.banking.events.DepositExecutionRequest;
import com.banking.events.TransferExecutionResult;
import com.banking.payment.client.AccountServiceClient;
import com.banking.payment.dto.DepositRequest;
import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.entity.OutboxEvent;
import com.banking.payment.entity.Transaction;
import com.banking.payment.entity.TransactionStatus;
import com.banking.payment.entity.TransactionType;
import com.banking.payment.repository.OutboxEventRepository;
import com.banking.payment.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import feign.FeignException;
import feign.Request;
import feign.Response;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Deposits as a saga.
 *
 * <p>A deposit is a cross-service dual write for exactly the reason a transfer is: the credit
 * commits in account-service's database and the ledger row commits here. Before this it was neither
 * — it mutated a balance in account-service and recorded nothing, which is why deposits never
 * appeared in transaction history and why the reconciliation invariant
 * {@code sum(debits) == sum(credits) == sum(balance deltas)} could not be computed at all.
 *
 * <p>The tests split in two: the first group pins the saga's ordering and failure classification,
 * the second pins what a deposit row and its event actually look like — above all that the source
 * side stays null rather than being filled in with something plausible.
 */
@ExtendWith(MockitoExtension.class)
class DepositSagaTest {

    @Mock TransactionRepository transactionRepository;
    @Mock OutboxEventRepository outboxRepository;
    @Mock AccountServiceClient accountServiceClient;
    @Mock TransferLedger ledger;
    @Mock ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule()).build();

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID TO_ACCOUNT = UUID.randomUUID();
    private static final UUID INTENT_ID = UUID.randomUUID();

    private PaymentService paymentService() {
        return new PaymentService(transactionRepository, accountServiceClient, ledger);
    }

    private TransferLedger realLedger() {
        return new TransferLedger(transactionRepository, outboxRepository, objectMapper, eventPublisher);
    }

    private DepositRequest request(UUID key) {
        return new DepositRequest("000000000002", new BigDecimal("500.0000"), "branch cash-in", key);
    }

    private Transaction pendingDeposit(UUID key) {
        return Transaction.builder()
                .id(INTENT_ID).idempotencyKey(key)
                .toAccountNumber("000000000002")
                .amount(new BigDecimal("500.0000"))
                .type(TransactionType.DEPOSIT).status(TransactionStatus.PENDING)
                .build();
    }

    /** What account-service reports back: a credit has a destination side only. */
    private TransferExecutionResult depositResult() {
        return new TransferExecutionResult(null, "000000000002", null, new BigDecimal("625.0000"),
                null, RECIPIENT, TO_ACCOUNT);
    }

    private static FeignException feignError(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "/internal/accounts/execute-deposit",
                Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(status).reason("error").request(request)
                .headers(Map.of()).body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("executeDeposit", response);
    }

    // ---- the saga -------------------------------------------------------------------------

    /**
     * The ordering is the point, as it is for a transfer: credit first and the crash window leaves
     * money in an account with nothing in the ledger to account for it.
     */
    @Test
    void intentIsCommittedBeforeAccountServiceIsAskedToCredit() {
        UUID key = UUID.randomUUID();
        when(ledger.openDepositIntent(any())).thenReturn(pendingDeposit(key));
        when(accountServiceClient.executeDeposit(any())).thenReturn(depositResult());
        when(ledger.settleCompleted(eq(INTENT_ID), any())).thenReturn(pendingDeposit(key));

        paymentService().deposit(request(key));

        InOrder order = inOrder(ledger, accountServiceClient);
        order.verify(ledger).openDepositIntent(any());
        order.verify(accountServiceClient).executeDeposit(any());
        order.verify(ledger).settleCompleted(eq(INTENT_ID), any());
    }

    @Test
    void clientIdempotencyKeyIsForwardedToAccountService() {
        UUID key = UUID.randomUUID();
        when(ledger.openDepositIntent(any())).thenReturn(pendingDeposit(key));
        when(accountServiceClient.executeDeposit(any())).thenReturn(depositResult());
        when(ledger.settleCompleted(eq(INTENT_ID), any())).thenReturn(pendingDeposit(key));

        paymentService().deposit(request(key));

        ArgumentCaptor<DepositExecutionRequest> captor =
                ArgumentCaptor.forClass(DepositExecutionRequest.class);
        verify(accountServiceClient).executeDeposit(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(key);
        assertThat(captor.getValue().toAccountNumber()).isEqualTo("000000000002");
    }

    /**
     * The critical negative case, shared with the transfer path: on a timeout we do not know whether
     * the credit landed, so writing the intent off would contradict a balance that may have moved.
     */
    @Test
    void indeterminateFailureLeavesTheIntentPendingForRecovery() {
        UUID key = UUID.randomUUID();
        when(ledger.openDepositIntent(any())).thenReturn(pendingDeposit(key));
        when(accountServiceClient.executeDeposit(any()))
                .thenThrow(new IllegalStateException("read timed out after 5000ms"));

        assertThatThrownBy(() -> paymentService().deposit(request(key)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(ledger, never()).settleFailed(any(), any());
        verify(ledger, never()).settleCompleted(any(), any());
    }

    /** A 4xx is account-service's considered refusal — a frozen or unknown account. Nothing moved. */
    @Test
    void businessRejectionSettlesTheIntentFailedImmediately() {
        UUID key = UUID.randomUUID();
        when(ledger.openDepositIntent(any())).thenReturn(pendingDeposit(key));
        when(accountServiceClient.executeDeposit(any()))
                .thenThrow(feignError(400, "{\"status\":400,\"message\":\"Account is not active\"}"));

        assertThatThrownBy(() -> paymentService().deposit(request(key)))
                .isInstanceOf(AppException.class)
                .hasMessage("Account is not active");

        verify(ledger).settleFailed(eq(INTENT_ID), any());
        verify(ledger, never()).settleCompleted(any(), any());
    }

    /** Re-submitting a key returns the original deposit rather than crediting the account twice. */
    @Test
    void duplicateSubmitReturnsTheOriginalDepositWithoutCreditingAgain() {
        UUID key = UUID.randomUUID();
        Transaction settled = pendingDeposit(key);
        settled.setStatus(TransactionStatus.COMPLETED);

        when(ledger.openDepositIntent(any()))
                .thenThrow(new DataIntegrityViolationException("uk_transactions_idempotency_key"));
        when(ledger.findByIdempotencyKey(key)).thenReturn(Optional.of(settled));

        TransactionResponse response = paymentService().deposit(request(key));

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        verifyNoInteractions(accountServiceClient);
    }

    @Test
    void missingIdempotencyKeyIsRejectedBeforeAnIntentIsWritten() {
        assertThatThrownBy(() -> paymentService().deposit(request(null)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("idempotencyKey");

        verifyNoInteractions(ledger, accountServiceClient);
    }

    @Test
    void depositRequestDeclaresTheIdempotencyKeyAsRequired() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request(null)))
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("idempotencyKey");
        }
    }

    // ---- what a deposit row looks like ----------------------------------------------------

    /**
     * The source side stays null. A deposit whose {@code from} fields pointed at the destination
     * would look like a self-transfer to reconciliation — debits and credits would balance, and the
     * money that entered the system would go unnoticed.
     */
    @Test
    void depositIntentHasNoSourceSide() {
        UUID key = UUID.randomUUID();
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        realLedger().openDepositIntent(request(key));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction intent = captor.getValue();

        assertThat(intent.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(intent.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(intent.getIdempotencyKey()).isEqualTo(key);
        assertThat(intent.getFromAccountId()).isNull();
        assertThat(intent.getFromUserId()).isNull();
        assertThat(intent.getFromAccountNumber()).isNull();
    }

    /**
     * The event carries the row's own type. It used to be hardcoded to TRANSFER, which would have
     * told notification-service to notify a sender that does not exist for a deposit.
     */
    @Test
    void settlementPublishesTheEventAsADeposit() throws Exception {
        Transaction intent = pendingDeposit(UUID.randomUUID());
        when(transactionRepository.findById(INTENT_ID)).thenReturn(Optional.of(intent));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        realLedger().settleCompleted(INTENT_ID, depositResult());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.get("transactionType").asText()).isEqualTo("DEPOSIT");
        assertThat(payload.get("fromUserId").isNull()).as("no sender to notify").isTrue();
        assertThat(payload.get("toUserId").asText()).isEqualTo(RECIPIENT.toString());
        assertThat(new BigDecimal(payload.get("toAccountBalance").asText()))
                .isEqualByComparingTo("625.0000");
    }

    /** Settlement fills in only what account-service could resolve: the destination. */
    @Test
    void settlementFillsInTheDestinationAndLeavesTheSourceNull() {
        Transaction intent = pendingDeposit(UUID.randomUUID());
        when(transactionRepository.findById(INTENT_ID)).thenReturn(Optional.of(intent));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        Transaction settled = realLedger().settleCompleted(INTENT_ID, depositResult());

        assertThat(settled.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(settled.getToAccountId()).isEqualTo(TO_ACCOUNT);
        assertThat(settled.getToUserId()).isEqualTo(RECIPIENT);
        assertThat(settled.getFromAccountNumber()).isNull();
        assertThat(settled.getFromUserId()).isNull();
    }
}
