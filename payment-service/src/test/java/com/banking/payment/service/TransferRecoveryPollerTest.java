package com.banking.payment.service;

import com.banking.events.TransferExecutionResult;
import com.banking.payment.client.AccountServiceClient;
import com.banking.payment.entity.Transaction;
import com.banking.payment.entity.TransactionStatus;
import com.banking.payment.entity.TransactionType;
import com.banking.payment.repository.TransactionRepository;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The component that makes writing the intent worth anything.
 *
 * <p>A PENDING row means payment-service never learned whether the money moved. The poller asks
 * account-service — whose transfer log is written in the same transaction as the balance change,
 * making its answer authoritative — and settles accordingly. The behaviour worth protecting is the
 * asymmetry: "it happened" is acted on at once, while "it did not happen" has to survive a
 * write-off window first, because declaring a live transfer failed is the one unrecoverable move.
 */
@ExtendWith(MockitoExtension.class)
class TransferRecoveryPollerTest {

    @Mock TransactionRepository transactionRepository;
    @Mock AccountServiceClient accountServiceClient;
    @Mock TransferLedger ledger;

    TransferRecoveryPoller poller;

    private static final UUID INTENT_ID = UUID.randomUUID();
    private static final long WRITE_OFF_SECONDS = 900;

    @BeforeEach
    void setUp() {
        poller = new TransferRecoveryPoller(transactionRepository, accountServiceClient, ledger);
        ReflectionTestUtils.setField(poller, "gracePeriodSeconds", 60L);
        ReflectionTestUtils.setField(poller, "writeOffSeconds", WRITE_OFF_SECONDS);
    }

    private Transaction strandedIntent(UUID key, Instant createdAt) {
        return Transaction.builder()
                .id(INTENT_ID).idempotencyKey(key)
                .fromAccountId(UUID.randomUUID()).toAccountNumber("000000000002")
                .fromUserId(UUID.randomUUID()).amount(new BigDecimal("25.0000"))
                .type(TransactionType.TRANSFER).status(TransactionStatus.PENDING)
                .createdAt(createdAt)
                .build();
    }

    private static FeignException notFound() {
        Request request = Request.create(Request.HttpMethod.GET, "/internal/accounts/transfers/x",
                Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(404).reason("Not Found").request(request)
                .headers(Map.of()).body("{}", StandardCharsets.UTF_8).build();
        return FeignException.errorStatus("findTransfer", response);
    }

    private TransferExecutionResult result() {
        return new TransferExecutionResult("000000000001", "000000000002",
                new BigDecimal("75.0000"), new BigDecimal("125.0000"),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    /**
     * The case the saga exists for: money moved, then payment-service died before recording it.
     * Completing the row also writes the outbox event, so the notification the user never got is
     * finally sent.
     */
    @Test
    void anIntentWhoseMoneyMovedIsCompleted() {
        UUID key = UUID.randomUUID();
        when(transactionRepository.findStalePendingWithLock(any()))
                .thenReturn(List.of(strandedIntent(key, Instant.now().minusSeconds(120))));
        when(accountServiceClient.findTransfer(key)).thenReturn(result());

        poller.settleStrandedTransfers();

        verify(ledger).settleCompleted(eq(INTENT_ID), any());
        verify(ledger, never()).settleFailed(any(), any());
    }

    /**
     * Not-found is conclusive only once no in-flight request could still be running. Writing an
     * intent off early would mark a transfer failed while its money is still on the way — the
     * ledger would then contradict the balance, which is worse than leaving it PENDING.
     */
    @Test
    void aRecentlyCreatedIntentIsNotWrittenOffYet() {
        UUID key = UUID.randomUUID();
        when(transactionRepository.findStalePendingWithLock(any()))
                .thenReturn(List.of(strandedIntent(key, Instant.now().minusSeconds(120))));
        when(accountServiceClient.findTransfer(key)).thenThrow(notFound());

        poller.settleStrandedTransfers();

        verify(ledger, never()).settleFailed(any(), any());
        verify(ledger, never()).settleCompleted(any(), any());
    }

    /** Past the write-off window, absence of a log row means the money never moved and never will. */
    @Test
    void anOldIntentWithNoMatchingTransferIsWrittenOff() {
        UUID key = UUID.randomUUID();
        Instant old = Instant.now().minusSeconds(WRITE_OFF_SECONDS + 60);
        when(transactionRepository.findStalePendingWithLock(any()))
                .thenReturn(List.of(strandedIntent(key, old)));
        when(accountServiceClient.findTransfer(key)).thenThrow(notFound());

        poller.settleStrandedTransfers();

        verify(ledger).settleFailed(eq(INTENT_ID), contains("no matching transfer"));
    }

    /**
     * account-service being unreachable must leave the intent alone. Treating "I could not ask" as
     * "it did not happen" is exactly the wrong inference.
     */
    @Test
    void anUnreachableAccountServiceLeavesTheIntentPending() {
        UUID key = UUID.randomUUID();
        when(transactionRepository.findStalePendingWithLock(any()))
                .thenReturn(List.of(strandedIntent(key, Instant.now().minusSeconds(WRITE_OFF_SECONDS + 60))));
        when(accountServiceClient.findTransfer(key))
                .thenThrow(new IllegalStateException("connection refused"));

        poller.settleStrandedTransfers();

        verify(ledger, never()).settleFailed(any(), any());
        verify(ledger, never()).settleCompleted(any(), any());
    }

    /** One unresolvable intent must not stop the rest of the batch being settled. */
    @Test
    void oneFailingIntentDoesNotAbortTheBatch() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        Transaction badIntent = strandedIntent(bad, Instant.now().minusSeconds(120));
        Transaction goodIntent = strandedIntent(good, Instant.now().minusSeconds(120));
        goodIntent.setId(UUID.randomUUID());

        when(transactionRepository.findStalePendingWithLock(any()))
                .thenReturn(List.of(badIntent, goodIntent));
        when(accountServiceClient.findTransfer(bad)).thenThrow(new IllegalStateException("boom"));
        when(accountServiceClient.findTransfer(good)).thenReturn(result());

        poller.settleStrandedTransfers();

        verify(ledger).settleCompleted(eq(goodIntent.getId()), any());
    }

    @Test
    void nothingStrandedMeansNoCallsToAccountService() {
        when(transactionRepository.findStalePendingWithLock(any())).thenReturn(List.of());

        poller.settleStrandedTransfers();

        verify(accountServiceClient, never()).findTransfer(any());
    }
}
