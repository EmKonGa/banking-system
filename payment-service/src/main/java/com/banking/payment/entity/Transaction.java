package com.banking.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The client's key for this transfer attempt, and the deduplication point for the whole flow:
     * account-service dedupes the money on it, and the unique constraint here stops a retried
     * submit from writing a second ledger row for money that only moved once.
     */
    @Column(nullable = false, unique = true, updatable = false)
    private UUID idempotencyKey;

    @Column
    private UUID fromAccountId;

    /** Null while PENDING — only account-service can resolve the account number to an id. */
    @Column
    private UUID toAccountId;

    @Column
    private String fromAccountNumber;

    @Column(nullable = false)
    private String toAccountNumber;

    @Column
    private UUID fromUserId;

    @Column
    private UUID toUserId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    private String description;

    @CreationTimestamp
    private Instant createdAt;
}
