package com.banking.account.repository;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findByUserId(UUID userId);
    List<Account> findByUserIdAndStatusNot(UUID userId, AccountStatus status);
    boolean existsByAccountNumber(String accountNumber);
    Optional<Account> findByAccountNumber(String accountNumber);

    // Locks a single account row (SELECT ... FOR UPDATE) without mutating it. The transfer
    // flow calls this on both accounts in a deterministic id-ascending order so two opposing
    // concurrent transfers on the same pair acquire locks in the same order and cannot deadlock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    // Returns 1 if the debit succeeded, 0 if balance was insufficient or account inactive.
    // The WHERE clause makes the balance check and deduction a single atomic DB operation.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Account a SET a.balance = a.balance - :amount WHERE a.id = :id AND a.balance >= :amount AND a.status = 'ACTIVE'")
    int deductBalance(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Account a SET a.balance = a.balance + :amount WHERE a.id = :id AND a.status = 'ACTIVE'")
    int addBalance(@Param("id") UUID id, @Param("amount") BigDecimal amount);
}
