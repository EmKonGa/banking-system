package com.banking.payment.repository;

import com.banking.payment.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Returns a {@link Slice}, not a {@code Page}: a Page issues a second COUNT query over this
     * same OR predicate on every fetch, which costs more than the page itself and is only needed
     * to render a total page count. The UI paginates by "is there more", which {@code Slice}
     * answers by fetching one extra row.
     */
    @Query("SELECT t FROM Transaction t WHERE t.fromUserId = :userId OR t.toUserId = :userId ORDER BY t.createdAt DESC")
    Slice<Transaction> findByUserId(UUID userId, Pageable pageable);

    /**
     * Stays a {@code Page} — this one is served over Feign to account-service, and Spring Cloud
     * OpenFeign ships a {@code PageJacksonModule} to deserialize Page but has no Slice equivalent.
     * Switching it would need a hand-written DTO on both sides for no benefit here.
     */
    @Query("SELECT t FROM Transaction t WHERE t.fromAccountId = :accountId OR t.toAccountId = :accountId ORDER BY t.createdAt DESC")
    Page<Transaction> findByAccountId(UUID accountId, Pageable pageable);

    Optional<Transaction> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * Claims a batch of intents that have been PENDING longer than the grace period.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} for the same reason as {@code OutboxEventRepository}: with
     * more than one payment-service instance running, each claims its own rows instead of two
     * pollers settling the same intent twice. The {@code cutoff} keeps the poller away from
     * transfers that are merely in flight — a row younger than the grace period is far more likely
     * to be mid-request than stranded.
     */
    @Query(value = """
            SELECT * FROM transactions
            WHERE status = 'PENDING' AND created_at < :cutoff
            ORDER BY created_at ASC
            LIMIT 20
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Transaction> findStalePendingWithLock(@Param("cutoff") Instant cutoff);

    /**
     * What the ledger says each account should hold, for reconciliation.
     *
     * <p>Credits and debits are summed in two branches unioned together rather than with a
     * {@code CASE} over one scan, because a transfer contributes to <em>two</em> different accounts
     * from a single row — one as source, one as destination. There is no grouping key that yields
     * both from one pass.
     *
     * <p>Deposits have a null {@code from_account_id}, so they contribute a credit and no debit,
     * which is exactly the asymmetry that makes money entering the system visible here.
     */
    @Query(value = """
            SELECT account_id, SUM(delta) AS net FROM (
                SELECT to_account_id AS account_id, amount AS delta
                  FROM transactions
                 WHERE status = 'COMPLETED' AND to_account_id IS NOT NULL
                UNION ALL
                SELECT from_account_id AS account_id, -amount AS delta
                  FROM transactions
                 WHERE status = 'COMPLETED' AND from_account_id IS NOT NULL
            ) legs
            GROUP BY account_id
            ORDER BY account_id
            """,
            countQuery = """
            SELECT COUNT(DISTINCT account_id) FROM (
                SELECT to_account_id AS account_id FROM transactions
                 WHERE status = 'COMPLETED' AND to_account_id IS NOT NULL
                UNION ALL
                SELECT from_account_id AS account_id FROM transactions
                 WHERE status = 'COMPLETED' AND from_account_id IS NOT NULL
            ) legs
            """,
            nativeQuery = true)
    Page<LedgerNetProjection> findNetByAccount(Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.status = 'PENDING' AND t.createdAt < :cutoff")
    Page<Transaction> findStalePending(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** Native projection for {@link #findNetByAccount}: JPQL cannot express the UNION ALL above. */
    interface LedgerNetProjection {
        UUID getAccountId();
        BigDecimal getNet();
    }
}
