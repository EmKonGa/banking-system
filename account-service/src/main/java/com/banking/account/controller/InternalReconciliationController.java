package com.banking.account.controller;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountTransferLog;
import com.banking.account.repository.AccountRepository;
import com.banking.account.repository.AccountTransferLogRepository;
import com.banking.events.AccountBalanceSnapshot;
import com.banking.events.MovementKeySnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only snapshots for reconciliation-service.
 *
 * <p>These expose account-service's own view of the money — the balances it is authoritative for,
 * and the movements it committed — so an outside auditor can compare them against the ledger.
 * Nothing here interprets or judges: the invariants live in reconciliation-service, which is most of
 * the reason it is a separate service at all.
 *
 * <p>{@code Page} rather than {@code Slice}, unlike the user-facing endpoints: a sweep needs to know
 * how much is left to walk, and Spring Cloud OpenFeign can deserialize a {@code Page} but ships no
 * {@code Slice} equivalent.
 */
@RestController
@RequestMapping("/internal/accounts/reconciliation")
@RequiredArgsConstructor
public class InternalReconciliationController {

    private final AccountRepository accountRepository;
    private final AccountTransferLogRepository transferLogRepository;

    /**
     * Every account and what it currently holds — including CLOSED ones, which must still balance.
     * Closing is not a way to move money, so a closed account's history has to add up exactly as a
     * live one's does.
     */
    @GetMapping("/balances")
    public Page<AccountBalanceSnapshot> balances(@PageableDefault(size = 200) Pageable pageable) {
        return accountRepository.findAll(stableOrder(pageable, "id"))
                .map(InternalReconciliationController::toBalance);
    }

    /** The identity of every movement account-service has actually committed. */
    @GetMapping("/movement-keys")
    public Page<MovementKeySnapshot> movementKeys(@PageableDefault(size = 500) Pageable pageable) {
        return transferLogRepository.findAll(stableOrder(pageable, "idempotencyKey"))
                .map(InternalReconciliationController::toMovementKey);
    }

    private static AccountBalanceSnapshot toBalance(Account account) {
        return new AccountBalanceSnapshot(
                account.getId(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getStatus().name());
    }

    private static MovementKeySnapshot toMovementKey(AccountTransferLog log) {
        return new MovementKeySnapshot(
                log.getIdempotencyKey(),
                log.getType().name(),
                log.getCreatedAt());
    }

    /**
     * A stable total order is required, not merely tidy. Without an ORDER BY, Postgres may return
     * rows in a different order for each page, and a sweep paging through them would silently skip
     * some rows and count others twice — producing findings that are pure artefact.
     */
    private static Pageable stableOrder(Pageable pageable, String property) {
        return pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(property));
    }
}
