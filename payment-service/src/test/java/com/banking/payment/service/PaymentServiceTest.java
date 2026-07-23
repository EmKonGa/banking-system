package com.banking.payment.service;

import com.banking.common.exception.AppException;
import com.banking.common.security.JwtPrincipal;
import com.banking.events.TransferExecutionRequest;
import com.banking.events.TransferExecutionResult;
import com.banking.payment.client.AccountServiceClient;
import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.dto.TransferRequest;
import com.banking.payment.entity.Transaction;
import com.banking.payment.entity.TransactionStatus;
import com.banking.payment.entity.TransactionType;
import com.banking.payment.repository.TransactionRepository;
import feign.FeignException;
import feign.Request;
import feign.Response;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
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
 * Pins the transfer saga. Moving money is a call to another service that commits in its own
 * database, so the guarantee this class provides is not "both happen or neither" — it is that
 * <em>payment-service always holds evidence of what it attempted</em>. The tests below are mostly
 * about where the boundaries fall and what is left behind on each failure path.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock AccountServiceClient accountServiceClient;
    @Mock TransferLedger ledger;

    PaymentService paymentService;

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID FROM_ACCOUNT = UUID.randomUUID();
    private static final UUID TO_ACCOUNT = UUID.randomUUID();
    private static final UUID INTENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(transactionRepository, accountServiceClient, ledger);
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

    private Transaction pendingIntent(UUID key) {
        return Transaction.builder()
                .id(INTENT_ID).idempotencyKey(key)
                .fromAccountId(FROM_ACCOUNT).toAccountNumber("000000000002")
                .fromUserId(CALLER).amount(new BigDecimal("25.0000"))
                .type(TransactionType.TRANSFER).status(TransactionStatus.PENDING)
                .build();
    }

    private TransferExecutionResult executionResult() {
        return new TransferExecutionResult("000000000001", "000000000002",
                new BigDecimal("75.0000"), new BigDecimal("125.0000"),
                CALLER, RECIPIENT, TO_ACCOUNT);
    }

    private static FeignException feignError(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "/internal/accounts/execute-transfer",
                Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(status).reason("error").request(request)
                .headers(Map.of()).body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("executeTransfer", response);
    }

    /**
     * The ordering is the whole fix. If the money moves first, a crash before the ledger row
     * commits leaves payment-service with no record that a transfer was ever attempted — nothing to
     * retry from and nothing to reconcile against.
     */
    @Test
    void intentIsCommittedBeforeAccountServiceIsAskedToMoveMoney() {
        UUID key = UUID.randomUUID();
        when(ledger.openIntent(any(), eq(CALLER))).thenReturn(pendingIntent(key));
        when(accountServiceClient.executeTransfer(any())).thenReturn(executionResult());
        when(ledger.settleCompleted(eq(INTENT_ID), any())).thenReturn(pendingIntent(key));

        paymentService.transfer(request(key));

        InOrder order = inOrder(ledger, accountServiceClient);
        order.verify(ledger).openIntent(any(), eq(CALLER));
        order.verify(accountServiceClient).executeTransfer(any());
        order.verify(ledger).settleCompleted(eq(INTENT_ID), any());
    }

    /** The key account-service dedupes on has to be the client's, forwarded unchanged. */
    @Test
    void clientIdempotencyKeyIsForwardedToAccountService() {
        UUID key = UUID.randomUUID();
        when(ledger.openIntent(any(), eq(CALLER))).thenReturn(pendingIntent(key));
        when(accountServiceClient.executeTransfer(any())).thenReturn(executionResult());
        when(ledger.settleCompleted(eq(INTENT_ID), any())).thenReturn(pendingIntent(key));

        paymentService.transfer(request(key));

        ArgumentCaptor<TransferExecutionRequest> captor =
                ArgumentCaptor.forClass(TransferExecutionRequest.class);
        verify(accountServiceClient).executeTransfer(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(key);
        assertThat(captor.getValue().fromUserId()).isEqualTo(CALLER);
    }

    /**
     * A 4xx is account-service's considered refusal: it evaluated the request and did not move the
     * money. That answer is final, so the intent is settled immediately rather than left for the
     * recovery poller to rediscover.
     */
    @Test
    void businessRejectionSettlesTheIntentFailedImmediately() {
        UUID key = UUID.randomUUID();
        when(ledger.openIntent(any(), eq(CALLER))).thenReturn(pendingIntent(key));
        when(accountServiceClient.executeTransfer(any()))
                .thenThrow(feignError(422, "{\"status\":422,\"message\":\"Insufficient balance\"}"));

        assertThatThrownBy(() -> paymentService.transfer(request(key)))
                .isInstanceOf(AppException.class);

        verify(ledger).settleFailed(eq(INTENT_ID), any());
        verify(ledger, never()).settleCompleted(any(), any());
    }

    /**
     * account-service's own status and message must reach the caller. Nothing maps a raw
     * FeignException, so before this the user saw "insufficient balance" as a 500.
     */
    @Test
    void businessRejectionKeepsAccountServiceStatusAndMessage() {
        UUID key = UUID.randomUUID();
        when(ledger.openIntent(any(), eq(CALLER))).thenReturn(pendingIntent(key));
        when(accountServiceClient.executeTransfer(any()))
                .thenThrow(feignError(422, "{\"status\":422,\"message\":\"Insufficient balance\"}"));

        assertThatThrownBy(() -> paymentService.transfer(request(key)))
                .isInstanceOf(AppException.class)
                .hasMessage("Insufficient balance")
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /**
     * The critical negative case. On a timeout we do not know whether the money moved, so the
     * intent must be left PENDING — settling it FAILED here would write off a transfer that may
     * have succeeded, and the ledger would contradict the balance.
     */
    @Test
    void indeterminateFailureLeavesTheIntentPendingForRecovery() {
        UUID key = UUID.randomUUID();
        when(ledger.openIntent(any(), eq(CALLER))).thenReturn(pendingIntent(key));
        // What a read timeout surfaces as: unchecked, and carrying no HTTP status to classify by.
        when(accountServiceClient.executeTransfer(any()))
                .thenThrow(new IllegalStateException("read timed out after 5000ms"));

        assertThatThrownBy(() -> paymentService.transfer(request(key)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(ledger, never()).settleFailed(any(), any());
        verify(ledger, never()).settleCompleted(any(), any());
    }

    /** A 5xx is equally unknown — account-service may have committed before failing to respond. */
    @Test
    void serverErrorIsTreatedAsIndeterminateNotAsRejection() {
        UUID key = UUID.randomUUID();
        when(ledger.openIntent(any(), eq(CALLER))).thenReturn(pendingIntent(key));
        when(accountServiceClient.executeTransfer(any())).thenThrow(feignError(503, "{}"));

        assertThatThrownBy(() -> paymentService.transfer(request(key)))
                .isInstanceOf(AppException.class);

        verify(ledger, never()).settleFailed(any(), any());
    }

    /**
     * Re-submitting a key returns the original outcome instead of writing a second ledger row.
     * account-service already dedupes the money; without this the retry still double-counted the
     * transfer in history and sent the user a second notification.
     */
    @Test
    void duplicateSubmitReturnsTheOriginalTransferWithoutMovingMoneyAgain() {
        UUID key = UUID.randomUUID();
        Transaction settled = pendingIntent(key);
        settled.setStatus(TransactionStatus.COMPLETED);

        when(ledger.openIntent(any(), eq(CALLER)))
                .thenThrow(new DataIntegrityViolationException("uk_transactions_idempotency_key"));
        when(ledger.findByIdempotencyKey(key)).thenReturn(Optional.of(settled));

        TransactionResponse response = paymentService.transfer(request(key));

        assertThat(response.id()).isEqualTo(INTENT_ID);
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        verifyNoInteractions(accountServiceClient);
    }

    @Test
    void missingIdempotencyKeyIsRejectedBeforeAnIntentIsWritten() {
        assertThatThrownBy(() -> paymentService.transfer(request(null)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("idempotencyKey");

        verifyNoInteractions(ledger, accountServiceClient);
    }

    /** The contract is enforced at the edge too, so the request never reaches the service. */
    @Test
    void transferRequestDeclaresTheIdempotencyKeyAsRequired() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request(null)))
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("idempotencyKey");
        }
    }

    /**
     * The listing is scoped to the authenticated principal, never to a caller-supplied id — the
     * endpoint takes no user parameter precisely so one user cannot read another's ledger.
     */
    @Test
    void myTransactionsQueriesOnlyTheAuthenticatedUser() {
        Pageable pageable = PageRequest.of(0, 20);
        Transaction tx = pendingIntent(UUID.randomUUID());
        when(transactionRepository.findByUserId(CALLER, pageable))
                .thenReturn(new SliceImpl<>(List.of(tx), pageable, false));

        Slice<TransactionResponse> result = paymentService.myTransactions(pageable);

        assertThat(result.getContent()).extracting(TransactionResponse::id).containsExactly(tx.getId());
        verify(transactionRepository).findByUserId(CALLER, pageable);
    }
}
