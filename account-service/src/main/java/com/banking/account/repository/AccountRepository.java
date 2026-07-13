package com.banking.account.repository;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // Returns 1 if the debit succeeded, 0 if balance was insufficient or account inactive.
    // The WHERE clause makes the balance check and deduction a single atomic DB operation.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Account a SET a.balance = a.balance - :amount WHERE a.id = :id AND a.balance >= :amount AND a.status = 'ACTIVE'")
    int deductBalance(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Account a SET a.balance = a.balance + :amount WHERE a.id = :id AND a.status = 'ACTIVE'")
    int addBalance(@Param("id") UUID id, @Param("amount") BigDecimal amount);
}
