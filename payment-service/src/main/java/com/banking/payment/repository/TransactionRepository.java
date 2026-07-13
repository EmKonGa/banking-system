package com.banking.payment.repository;

import com.banking.payment.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    @Query("SELECT t FROM Transaction t WHERE t.fromUserId = :userId OR t.toUserId = :userId ORDER BY t.createdAt DESC")
    Page<Transaction> findByUserId(UUID userId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.fromAccountId = :accountId OR t.toAccountId = :accountId ORDER BY t.createdAt DESC")
    Page<Transaction> findByAccountId(UUID accountId, Pageable pageable);
}
