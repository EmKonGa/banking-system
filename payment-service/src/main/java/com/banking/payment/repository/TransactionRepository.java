package com.banking.payment.repository;

import com.banking.payment.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
