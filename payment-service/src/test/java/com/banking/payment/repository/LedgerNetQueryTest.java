package com.banking.payment.repository;

import com.banking.payment.entity.Transaction;
import com.banking.payment.entity.TransactionStatus;
import com.banking.payment.entity.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ledger aggregate reconciliation compares balances against, run against a real PostgreSQL.
 *
 * <p>It has to be a native query — a transfer contributes to two different accounts from one row,
 * so there is no grouping key that yields both sides in a single pass, and JPQL cannot express the
 * UNION ALL that does. Native SQL plus an interface projection is exactly the combination that
 * compiles happily and then fails to map at runtime, so it is worth pinning against the real
 * database rather than a mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class LedgerNetQueryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Hibernate's default_schema applies to entity mappings only. A native query is passed
        // through untouched and resolves against the connection's search_path, which in production
        // comes from currentSchema in the JDBC URL — a URL @ServiceConnection replaces. Setting it
        // per connection restores the production behaviour without hand-assembling the container's
        // URL, and SET search_path succeeds even before Flyway has created the schema.
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO banking_payment");

        registry.add("app.jwt.secret", () -> "test-jwt-secret-that-is-long-enough-for-hs256-256bits");
        registry.add("internal.secret", () -> "test-internal-secret");
        registry.add("management.tracing.enabled", () -> "false");
    }

    @Autowired
    TransactionRepository transactionRepository;

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @BeforeEach
    void clean() {
        transactionRepository.deleteAllInBatch();
    }

    private void transfer(UUID from, UUID to, String amount, TransactionStatus status) {
        transactionRepository.save(Transaction.builder()
                .idempotencyKey(UUID.randomUUID())
                .fromAccountId(from).toAccountId(to)
                .toAccountNumber("000000000002")
                .amount(new BigDecimal(amount))
                .type(TransactionType.TRANSFER).status(status)
                .build());
    }

    private void deposit(UUID to, String amount, TransactionStatus status) {
        transactionRepository.save(Transaction.builder()
                .idempotencyKey(UUID.randomUUID())
                .toAccountId(to)
                .toAccountNumber("000000000002")
                .amount(new BigDecimal(amount))
                .type(TransactionType.DEPOSIT).status(status)
                .build());
    }

    private Map<UUID, BigDecimal> net() {
        return transactionRepository.findNetByAccount(PageRequest.of(0, 100)).getContent().stream()
                .collect(Collectors.toMap(
                        TransactionRepository.LedgerNetProjection::getAccountId,
                        TransactionRepository.LedgerNetProjection::getNet));
    }

    /** The mapping itself: native column aliases have to reach the projection's getters. */
    @Test
    void theProjectionMapsAccountAndNet() {
        deposit(ALICE, "500.0000", TransactionStatus.COMPLETED);

        assertThat(net()).hasSize(1);
        assertThat(net().get(ALICE)).isEqualByComparingTo("500.0000");
    }

    /** One row, two accounts, opposite signs — the reason a single GROUP BY cannot do this. */
    @Test
    void aTransferDebitsTheSourceAndCreditsTheDestination() {
        transfer(ALICE, BOB, "125.0000", TransactionStatus.COMPLETED);

        assertThat(net().get(ALICE)).isEqualByComparingTo("-125.0000");
        assertThat(net().get(BOB)).isEqualByComparingTo("125.0000");
    }

    /**
     * A deposit has a null source, so it contributes a credit and no debit. That asymmetry is what
     * makes money entering the system visible to reconciliation at all — and a null from_account_id
     * silently forming its own group would have been the quiet way to get this wrong.
     */
    @Test
    void aDepositCreditsWithoutDebitingAnything() {
        deposit(BOB, "500.0000", TransactionStatus.COMPLETED);

        assertThat(net()).containsOnlyKeys(BOB);
        assertThat(net().get(BOB)).isEqualByComparingTo("500.0000");
    }

    /**
     * PENDING is excluded because its money may or may not have moved — that is what the state
     * means. Counting it would make the reconciler disagree with reality on every transfer that is
     * merely in flight, and the alert would never be quiet.
     */
    @Test
    void pendingAndFailedRowsAreExcluded() {
        transfer(ALICE, BOB, "125.0000", TransactionStatus.PENDING);
        transfer(ALICE, BOB, "300.0000", TransactionStatus.FAILED);
        deposit(BOB, "50.0000", TransactionStatus.COMPLETED);

        assertThat(net()).containsOnlyKeys(BOB);
        assertThat(net().get(BOB)).isEqualByComparingTo("50.0000");
    }

    @Test
    void severalMovementsOnOneAccountSumTogether() {
        deposit(ALICE, "1000.0000", TransactionStatus.COMPLETED);
        transfer(ALICE, BOB, "250.0000", TransactionStatus.COMPLETED);
        transfer(BOB, ALICE, "100.0000", TransactionStatus.COMPLETED);

        assertThat(net().get(ALICE)).isEqualByComparingTo("850.0000");
        assertThat(net().get(BOB)).isEqualByComparingTo("150.0000");
    }

    /** The count query drives pagination; a wrong one silently truncates a sweep. */
    @Test
    void theCountQueryCountsDistinctAccountsNotRows() {
        deposit(ALICE, "10.0000", TransactionStatus.COMPLETED);
        deposit(ALICE, "20.0000", TransactionStatus.COMPLETED);
        transfer(ALICE, BOB, "5.0000", TransactionStatus.COMPLETED);

        assertThat(transactionRepository.findNetByAccount(PageRequest.of(0, 100)).getTotalElements())
                .as("two accounts, not four legs")
                .isEqualTo(2);
    }
}
