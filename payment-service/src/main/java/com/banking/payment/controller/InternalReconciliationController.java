package com.banking.payment.controller;

import com.banking.events.LedgerKeySnapshot;
import com.banking.events.LedgerNetSnapshot;
import com.banking.payment.entity.Transaction;
import com.banking.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Read-only ledger snapshots for reconciliation-service.
 *
 * <p>The aggregate below is computed here rather than by streaming every transaction to the
 * reconciler: the comparison is independent either way, and a sum belongs where the rows are.
 */
@RestController
@RequestMapping("/internal/payments/reconciliation")
@RequiredArgsConstructor
public class InternalReconciliationController {

    private final TransactionRepository transactionRepository;

    /**
     * What the ledger says each account should hold: credits minus debits over COMPLETED rows only.
     *
     * <p>PENDING is excluded because its money may or may not have moved — that is the entire
     * meaning of the state — and FAILED because its money definitively did not. Including either
     * would make the reconciler disagree with reality on every transfer that is merely in flight.
     */
    @GetMapping("/net-by-account")
    public Page<LedgerNetSnapshot> netByAccount(@PageableDefault(size = 200) Pageable pageable) {
        // The query carries its own ORDER BY account_id, so no stableOrder() wrapper here — adding
        // a Sort would append a second ORDER BY to the native SQL and fail.
        return transactionRepository.findNetByAccount(pageable)
                .map(p -> new LedgerNetSnapshot(p.getAccountId(), p.getNet()));
    }

    /**
     * The identity and settled state of every ledger row. Status travels with the key rather than
     * being filtered here, so the reconciler can tell a bad write-off (a movement account-service
     * committed whose ledger row says FAILED) from a lost settlement (no ledger row at all).
     */
    @GetMapping("/keys")
    public Page<LedgerKeySnapshot> keys(@PageableDefault(size = 500) Pageable pageable) {
        return transactionRepository.findAll(stableOrder(pageable))
                .map(InternalReconciliationController::toKey);
    }

    /**
     * Intents the recovery poller should already have settled. A row still PENDING well past the
     * write-off window does not mean money is lost — it means the mechanism that decides is itself
     * stuck, which is otherwise entirely silent.
     */
    @GetMapping("/stale-pending")
    public Page<LedgerKeySnapshot> stalePending(
            @RequestParam(defaultValue = "1800") long olderThanSeconds,
            @PageableDefault(size = 200) Pageable pageable) {
        return transactionRepository
                .findStalePending(Instant.now().minusSeconds(olderThanSeconds), stableOrder(pageable))
                .map(InternalReconciliationController::toKey);
    }

    private static LedgerKeySnapshot toKey(Transaction tx) {
        return new LedgerKeySnapshot(
                tx.getId(),
                tx.getIdempotencyKey(),
                tx.getStatus().name(),
                tx.getCreatedAt());
    }

    /** See the note on account-service's equivalent: unordered pages skip and duplicate rows. */
    private static Pageable stableOrder(Pageable pageable) {
        return pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("id"));
    }
}
