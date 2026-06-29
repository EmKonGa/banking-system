package com.banking.payment.repository;

import com.banking.payment.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    @Query("SELECT DISTINCT t FROM Transaction t " +
           "LEFT JOIN t.fromAccount fa " +
           "JOIN t.toAccount ta " +
           "WHERE fa.user.id = :userId OR ta.user.id = :userId " +
           "ORDER BY t.createdAt DESC")
    List<Transaction> findByUserId(UUID userId);

    @Query("SELECT t FROM Transaction t " +
           "WHERE t.fromAccount.id = :accountId OR t.toAccount.id = :accountId " +
           "ORDER BY t.createdAt DESC")
    List<Transaction> findByAccountId(UUID accountId);

    boolean existsByFromAccountIdOrToAccountId(UUID fromAccountId, UUID toAccountId);
}
