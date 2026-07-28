package com.banking.account.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Insert-only idempotency record, written in the same transaction as the balance change it
 * describes. That co-commit is what lets payment-service's recovery poller treat the absence of a
 * row as proof that no money moved for a given key.
 *
 * <p>It covers every kind of movement, not only transfers — a {@link MovementType#DEPOSIT} row
 * leaves the {@code from_*} fields null, because the money came from outside the system.
 *
 * <p>Implements {@link Persistable} with {@code isNew() == true} so {@code save()} always issues an
 * INSERT (via {@code persist}) rather than a {@code merge} upsert. This is what makes a duplicate
 * idempotency key raise a constraint violation (→ DataIntegrityViolationException) instead of
 * silently updating the existing row — the behaviour the idempotency handling relies on, especially
 * under concurrency.
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementType type;

    /** Null on a deposit — there is no source account. Same for the three fields below it. */
    @Column
    private UUID fromAccountId;

    @Column(nullable = false)
    private UUID toAccountId;

    @Column
    private UUID fromUserId;

    @Column(nullable = false)
    private UUID toUserId;

    @Column
    private String fromAccountNumber;

    @Column(nullable = false)
    private String toAccountNumber;

    @Column(precision = 19, scale = 4)
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
