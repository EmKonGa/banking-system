package com.banking.payment.service;

import com.banking.events.TransferExecutionResult;
import com.banking.payment.client.AccountServiceClient;
import com.banking.payment.entity.Transaction;
import com.banking.payment.repository.TransactionRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Settles transfer intents whose outcome payment-service never learned, by asking account-service
 * what the idempotency key did.
 *
 * <p>It resolves rather than replays. Re-POSTing {@code execute-transfer} would be safe from a
 * duplication standpoint — that endpoint is idempotent — but it would <em>execute</em> a transfer
 * whose original attempt may never have arrived, long after the user gave up, against balances that
 * have since changed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferRecoveryPoller {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final TransferLedger ledger;

    /** How long an intent may be in flight before the poller treats it as stranded. */
    @Value("${transfer.recovery.grace-period-seconds:60}")
    private long gracePeriodSeconds;

    /**
     * How old a not-found intent must be before it is written off. Comfortably beyond the grace
     * period so a transfer that is merely slow is never declared failed while still in flight.
     */
    @Value("${transfer.recovery.write-off-seconds:900}")
    private long writeOffSeconds;

    /** The transaction exists to hold the {@code FOR UPDATE SKIP LOCKED} claim on the batch. */
    @Scheduled(fixedDelayString = "${transfer.recovery.poll-interval-ms:30000}")
    @Transactional
    public void settleStrandedTransfers() {
        Instant cutoff = Instant.now().minusSeconds(gracePeriodSeconds);
        List<Transaction> stranded = transactionRepository.findStalePendingWithLock(cutoff);

        if (stranded.isEmpty()) return;

        log.info("[SAGA-RECOVERY] resolving {} stranded intent(s)", stranded.size());
        for (Transaction intent : stranded) {
            try {
                resolve(intent);
            } catch (Exception e) {
                // Leave it PENDING and try again next tick — an unresolvable intent is exactly the
                // thing that must not be silently written off.
                log.error("[SAGA-RECOVERY] could not resolve intent {}: {}",
                        intent.getId(), e.toString());
            }
        }
    }

    private void resolve(Transaction intent) {
        TransferExecutionResult result;
        try {
            result = accountServiceClient.findTransfer(intent.getIdempotencyKey());
        } catch (FeignException.NotFound e) {
            writeOffIfOldEnough(intent);
            return;
        }

        ledger.settleCompleted(intent.getId(), result);
        log.warn("[SAGA-RECOVERY] intent {} (key {}) had moved money with no ledger row — completed",
                intent.getId(), intent.getIdempotencyKey());
    }

    private void writeOffIfOldEnough(Transaction intent) {
        Instant writeOffBefore = Instant.now().minusSeconds(writeOffSeconds);
        if (intent.getCreatedAt() != null && intent.getCreatedAt().isAfter(writeOffBefore)) {
            // account-service commits the log row with the balance change, so "not found" is only
            // conclusive once no in-flight request could still be running.
            log.debug("[SAGA-RECOVERY] intent {} not found yet, still within the write-off window",
                    intent.getId());
            return;
        }
        ledger.settleFailed(intent.getId(), "no matching transfer in account-service after "
                + Duration.ofSeconds(writeOffSeconds).toMinutes() + "m");
    }
}
