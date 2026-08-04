package com.banking.reconciliation;

import com.banking.reconciliation.entity.FindingStatus;
import com.banking.reconciliation.entity.FindingType;
import com.banking.reconciliation.entity.ReconciliationFinding;
import com.banking.reconciliation.entity.SweepState;
import com.banking.reconciliation.repository.ReconciliationFindingRepository;
import com.banking.reconciliation.repository.SweepStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the service against a real PostgreSQL, which is what proves V1 and the entity agree —
 * {@code ddl-auto=validate} fails startup otherwise.
 *
 * <p>It also pins the uniqueness that makes the register a register: one discrepancy is one row,
 * however many sweeps see it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class FindingSchemaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO banking_reconciliation");
        registry.add("app.jwt.secret", () -> "test-jwt-secret-that-is-long-enough-for-hs256-256bits");
        registry.add("internal.secret", () -> "test-internal-secret");
        registry.add("management.tracing.enabled", () -> "false");
        // Keep the scheduled sweep out of this test: it would try to reach two services that are
        // not running. It aborts safely, but the noise buys nothing here.
        registry.add("reconciliation.initial-delay-ms", () -> "3600000");
    }

    @Autowired
    ReconciliationFindingRepository findings;

    @Autowired
    SweepStateRepository sweepState;

    private ReconciliationFinding finding(FindingType type, UUID subject) {
        return ReconciliationFinding.builder()
                .type(type)
                .subjectId(subject)
                .status(FindingStatus.SUSPECTED)
                .detail("test")
                .timesSeen(1)
                .lastSeenAt(Instant.now())
                .build();
    }

    @Test
    void aFindingRoundTrips() {
        UUID subject = UUID.randomUUID();

        findings.save(finding(FindingType.BALANCE_MISMATCH, subject));

        assertThat(findings.findByTypeAndSubjectId(FindingType.BALANCE_MISMATCH, subject))
                .isPresent();
    }

    /**
     * The constraint the whole design rests on: a mismatch that survives twenty sweeps is one
     * finding seen twenty times, not twenty findings. Without it an alert on open findings would
     * grow with sweep frequency rather than with how much is actually wrong.
     */
    @Test
    void oneSubjectCannotAccumulateDuplicateFindingsOfTheSameType() {
        UUID subject = UUID.randomUUID();
        findings.saveAndFlush(finding(FindingType.BALANCE_MISMATCH, subject));

        assertThatThrownBy(() ->
                findings.saveAndFlush(finding(FindingType.BALANCE_MISMATCH, subject)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Different kinds of problem about the same subject are genuinely different findings. */
    @Test
    void oneSubjectMayHaveFindingsOfDifferentTypes() {
        UUID subject = UUID.randomUUID();

        findings.saveAndFlush(finding(FindingType.BALANCE_MISMATCH, subject));
        findings.saveAndFlush(finding(FindingType.STUCK_INTENT, subject));

        assertThat(findings.findUnresolved()).hasSize(2);
    }

    /**
     * Two things about the liveness marker, in one test because they share a row and the class is
     * not transactional — split apart they would depend on JUnit's method order.
     *
     * <p>It starts <em>absent</em>: V2 deliberately seeds nothing, so the gauge reads epoch 0 until
     * a sweep completes. A service that has never audited anything alerts exactly like one that has
     * stopped, which is the right equivalence.
     *
     * <p>And it stays singular. "When did the sweep last finish" has to have one answer; a second
     * row would leave the gauge reporting whichever the database happened to return, making the
     * liveness signal correct only sometimes.
     */
    @Test
    void theLivenessMarkerStartsAbsentAndOnlyEverHoldsOneRow() {
        assertThat(sweepState.findById(SweepState.SINGLETON_ID))
                .as("V2 seeds no row — never-swept must be distinguishable, not assumed fresh")
                .isEmpty();

        sweepState.saveAndFlush(new SweepState(SweepState.SINGLETON_ID, Instant.now()));

        assertThatThrownBy(() ->
                sweepState.saveAndFlush(new SweepState((short) 2, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
