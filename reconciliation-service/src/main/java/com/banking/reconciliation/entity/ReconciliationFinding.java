package com.banking.reconciliation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One discrepancy between what account-service holds and what the ledger says it should.
 *
 * <p>Keyed by {@code (type, subject_id)} rather than by occurrence: a mismatch that persists across
 * twenty sweeps is one finding seen twenty times, not twenty findings. That is what makes
 * {@code times_seen} meaningful and keeps an alert on open findings from screaming proportionally
 * to sweep frequency.
 */
@Entity
@Table(
        name = "reconciliation_findings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_findings_type_subject", columnNames = {"type", "subject_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FindingType type;

    /** The account id or idempotency key the finding is about — whichever identifies the subject. */
    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FindingStatus status;

    /** What account-service holds, for a balance mismatch. Null for the key-set findings. */
    @Column(precision = 19, scale = 4)
    private BigDecimal observed;

    /** What the ledger says it should hold. Null for the key-set findings. */
    @Column(precision = 19, scale = 4)
    private BigDecimal expected;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(nullable = false)
    private int timesSeen;

    @CreationTimestamp
    private Instant firstSeenAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    private Instant resolvedAt;
}
