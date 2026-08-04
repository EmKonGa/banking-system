package com.banking.reconciliation.service;

import com.banking.events.AccountBalanceSnapshot;
import com.banking.events.LedgerKeySnapshot;
import com.banking.events.LedgerNetSnapshot;
import com.banking.events.MovementKeySnapshot;
import com.banking.reconciliation.client.AccountSnapshotClient;
import com.banking.reconciliation.client.PaymentLedgerClient;
import com.banking.reconciliation.entity.FindingType;
import com.banking.reconciliation.service.FindingRecorder.Observation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * The auditor. Compares what account-service holds against what the ledger says it should, and
 * records where they disagree.
 *
 * <p><strong>It detects; it never corrects.</strong> A reconciler that adjusts balances can destroy
 * money whenever its own logic is wrong, and its logic is exactly the thing that has no second
 * opinion. Same stance as the transfer recovery poller resolving rather than replaying: establish
 * what is true, report it, let a human decide.
 *
 * <p>It reads both sides from their owners' own storage rather than from the event stream. A
 * reconciler rebuilt from the same events the ledger consumes would share a failure mode with the
 * thing it audits — a lost event would be invisible to both, and the two would agree perfectly about
 * a system that had lost money.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationSweeper {

    private final AccountSnapshotClient accounts;
    private final PaymentLedgerClient ledger;
    private final FindingRecorder recorder;

    @Value("${reconciliation.page-size:200}")
    private int pageSize;

    /**
     * How old a PENDING intent must be before the recovery poller is presumed stuck. Comfortably
     * beyond that poller's own write-off window, or this would flag transfers it is still entitled
     * to be working on.
     */
    @Value("${reconciliation.stuck-intent-seconds:1800}")
    private long stuckIntentSeconds;

    /**
     * Ceiling on how many identities one sweep will hold in memory for the set comparison. Reaching
     * it means the comparison would be incomplete, which is reported rather than silently truncated
     * — a partial sweep that looks clean is worse than no sweep.
     */
    @Value("${reconciliation.max-keys:200000}")
    private int maxKeys;

    /**
     * The initial delay is not padding. A {@code fixedDelay} schedule fires once immediately at
     * startup, and a sweep that runs before account-service and payment-service are answering
     * cannot fetch a snapshot — it would abort, which is harmless, but on a rolling restart it also
     * means the first useful sweep waits a full interval. Starting a minute in costs nothing and
     * makes the first pass a real one.
     */
    @Scheduled(
            fixedDelayString = "${reconciliation.sweep-interval-ms:300000}",
            initialDelayString = "${reconciliation.initial-delay-ms:60000}")
    public void sweep() {
        try {
            List<Observation> observations = new ArrayList<>();
            observations.addAll(checkBalancesAgainstLedger());
            observations.addAll(checkMovementsAgainstLedger());
            observations.addAll(checkForStuckIntents());

            FindingRecorder.SweepOutcome outcome = recorder.record(observations);

            if (outcome.observed() == 0) {
                log.info("[RECON] sweep clean ({} finding(s) resolved)", outcome.resolved());
            } else {
                log.warn("[RECON] sweep saw {} discrepancy(ies), {} confirmed, {} resolved",
                        outcome.observed(), outcome.confirmed(), outcome.resolved());
            }
        } catch (Exception e) {
            // A sweep that cannot complete must change nothing. Recording a partial pass would
            // resolve findings merely because this run never got far enough to see them. Nothing is
            // recorded, which also leaves the liveness marker where it was — an outage that
            // persists surfaces as a stale reconciliation_last_successful_sweep_timestamp_seconds
            // rather than as findings that quietly stop being updated.
            log.error("[RECON] sweep aborted, register left untouched: {}", e.toString());
        }
    }

    /**
     * The headline invariant: an account holds exactly what its settled ledger rows add up to.
     *
     * <p>Accounts start at zero, so a missing ledger entry shows up as a balance larger than the
     * ledger explains — which is precisely how the unledgered-deposit bug would have surfaced. An
     * account with no movements at all is absent from the ledger aggregate and expected to hold
     * nothing.
     */
    private List<Observation> checkBalancesAgainstLedger() {
        Map<UUID, BigDecimal> ledgerNet = new HashMap<>();
        forEachPage(page -> ledger.netByAccount(page, pageSize),
                (LedgerNetSnapshot net) -> ledgerNet.put(net.accountId(), net.net()));

        List<Observation> observations = new ArrayList<>();
        forEachPage(page -> accounts.balances(page, pageSize), (AccountBalanceSnapshot account) -> {
            BigDecimal expected = ledgerNet.getOrDefault(account.accountId(), BigDecimal.ZERO);
            // compareTo, not equals: BigDecimal("0.00") and BigDecimal("0") are not equal, and the
            // two services' JSON round-trips need not preserve scale.
            if (account.balance().compareTo(expected) != 0) {
                observations.add(new Observation(
                        FindingType.BALANCE_MISMATCH,
                        account.accountId(),
                        account.balance(),
                        expected,
                        "account %s holds %s but the ledger accounts for %s"
                                .formatted(account.accountNumber(), account.balance(), expected)));
            }
        });
        return observations;
    }

    /**
     * The two services agree about which movements happened.
     *
     * <p>Both directions matter and mean opposite things. A movement account-service committed with
     * no COMPLETED ledger row is money that moved unrecorded — including the case where the row
     * exists but says FAILED, which is a transfer written off after its money had already moved. A
     * COMPLETED ledger row with no movement behind it is the ledger claiming a transfer that never
     * happened.
     */
    private List<Observation> checkMovementsAgainstLedger() {
        Map<UUID, String> ledgerStatusByKey = new HashMap<>();
        forEachPage(page -> ledger.keys(page, pageSize),
                (LedgerKeySnapshot key) -> ledgerStatusByKey.put(key.idempotencyKey(), key.status()));

        Set<UUID> movementKeys = new HashSet<>();
        List<Observation> observations = new ArrayList<>();

        forEachPage(page -> accounts.movementKeys(page, pageSize), (MovementKeySnapshot movement) -> {
            movementKeys.add(movement.idempotencyKey());
            String status = ledgerStatusByKey.get(movement.idempotencyKey());
            if (!"COMPLETED".equals(status)) {
                observations.add(Observation.of(
                        FindingType.MOVEMENT_NOT_LEDGERED,
                        movement.idempotencyKey(),
                        "account-service committed a %s but the ledger says %s"
                                .formatted(movement.type(), status == null ? "nothing at all" : status)));
            }
        });

        ledgerStatusByKey.forEach((key, status) -> {
            if ("COMPLETED".equals(status) && !movementKeys.contains(key)) {
                observations.add(Observation.of(
                        FindingType.LEDGER_WITHOUT_MOVEMENT, key,
                        "ledger records a COMPLETED movement account-service never committed"));
            }
        });

        // This sweep is complete — paging throws rather than truncating, so reaching here means
        // every key was read. What it reports is that the *next* one may not be: past the ceiling
        // forEachPage aborts the sweep, and it keeps aborting, so the service stops auditing
        // permanently while the findings gauges hold their last clean value. The staleness alert on
        // the liveness marker is what actually catches that; this is the warning that precedes it.
        if (ledgerStatusByKey.size() >= maxKeys || movementKeys.size() >= maxKeys) {
            log.error("[RECON] key-set comparison is at the {} ceiling — sweeps will begin aborting "
                    + "outright once it is crossed", maxKeys);
        }
        return observations;
    }

    /**
     * The recovery poller is still doing its job. This one needs no cross-service comparison —
     * an intent PENDING well past its write-off window is self-evidently unsettled, and the poller
     * failing is otherwise completely silent.
     */
    private List<Observation> checkForStuckIntents() {
        List<Observation> observations = new ArrayList<>();
        forEachPage(page -> ledger.stalePending(stuckIntentSeconds, page, pageSize),
                (LedgerKeySnapshot intent) -> observations.add(Observation.of(
                        FindingType.STUCK_INTENT, intent.transactionId(),
                        "intent still PENDING since %s — recovery has not settled it"
                                .formatted(intent.createdAt()))));
        return observations;
    }

    /**
     * Walks every page of a snapshot endpoint.
     *
     * <p>The bound is not defensive padding: without it a page that never reports itself as last —
     * a paging bug on either side, or rows being inserted faster than they are read — would spin
     * forever inside a scheduled task and take the sweep with it.
     */
    private <T> void forEachPage(IntFunction<Page<T>> fetch, java.util.function.Consumer<T> consume) {
        int pageNumber = 0;
        int guard = maxKeys / Math.max(pageSize, 1) + 2;
        while (pageNumber < guard) {
            Page<T> page = fetch.apply(pageNumber);
            page.getContent().forEach(consume);
            if (page.isLast() || page.getContent().isEmpty()) return;
            pageNumber++;
        }
        throw new IllegalStateException(
                "snapshot paging exceeded " + guard + " pages — refusing to record a partial sweep");
    }
}
