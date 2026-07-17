package com.banking.account.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Insert-only idempotency record. Implements {@link Persistable} with {@code isNew() == true}
 * so {@code save()} always issues an INSERT (via {@code persist}) rather than a {@code merge}
 * upsert. This is what makes a duplicate idempotency key raise a constraint violation
 * (→ DataIntegrityViolationException) instead of silently updating the existing row — the
 * behaviour the transfer idempotency handling relies on, especially under concurrency.
 */
@Entity
@Table(name = "account_transfer_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountTransferLog implements Persistable<UUID> {

    @Id
    private UUID idempotencyKey;

    @Column(nullable = false)
    private UUID fromAccountId;

    @Column(nullable = false)
    private UUID toAccountId;

    @Column(nullable = false)
    private UUID fromUserId;

    @Column(nullable = false)
    private UUID toUserId;

    @Column(nullable = false)
    private String fromAccountNumber;

    @Column(nullable = false)
    private String toAccountNumber;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal fromBalance;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal toBalance;

    @CreationTimestamp
    private Instant createdAt;

    @Override
    public UUID getId() {
        return idempotencyKey;
    }

    /** Always an INSERT — a duplicate key must fail loudly, never upsert. */
    @Override
    @Transient
    public boolean isNew() {
        return true;
    }
}
