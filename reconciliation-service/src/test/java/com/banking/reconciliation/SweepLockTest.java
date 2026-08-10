package com.banking.reconciliation;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock that keeps the sweep a singleton. Exercised through the real {@link LockProvider} against
 * a real PostgreSQL, because both things that can go wrong here are invisible to a mock: the table
 * being resolved in the wrong schema, and a second holder being admitted.
 *
 * <p>What is at stake is not duplicated work. Two overlapping sweeps resolve each other's findings
 * — each {@code resolveUnseen} pass clears everything absent from its own observation set — so
 * {@code times_seen} never reaches 2, nothing is ever promoted to CONFIRMED, and the alerting gauge
 * counts CONFIRMED only. Both replicas meanwhile keep the liveness marker fresh, so the staleness
 * alert stays quiet. The auditor goes blind and reports itself healthy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class SweepLockTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-that-is-long-enough-for-hs256-256bits");
        registry.add("internal.secret", () -> "test-internal-secret");
        registry.add("management.tracing.enabled", () -> "false");
        // Keep the scheduled sweep out of this test — it would reach for two services that are not
        // running, and it would take the very lock these assertions are about.
        registry.add("reconciliation.initial-delay-ms", () -> "3600000");
    }

    @Autowired
    LockProvider lockProvider;

    @Autowired
    JdbcTemplate jdbc;

    private static LockConfiguration config(String name, Duration atMostFor, Duration atLeastFor) {
        return new LockConfiguration(Instant.now(), name, atMostFor, atLeastFor);
    }

    /**
     * Deliberately no {@code connection-init-sql} setting the search_path, unlike
     * {@code FindingSchemaTest} — this test is the assertion that ShedLock does not need one.
     * ShedLock issues plain JDBC, so {@code hibernate.default_schema} does not reach it and
     * {@code @ServiceConnection} has replaced the URL that carried {@code ?currentSchema=}. If the
     * provider were configured with a bare table name it would look in {@code public} and fail here.
     */
    @Test
    void theLockTableIsFoundWithoutRelyingOnTheConnectionSearchPath() {
        Optional<SimpleLock> lock = lockProvider.lock(config("schemaProbe", Duration.ofMinutes(1), Duration.ZERO));

        assertThat(lock).isPresent();
        lock.get().unlock();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking_reconciliation.shedlock WHERE name = 'schemaProbe'",
                Integer.class)).isEqualTo(1);
    }

    /** The point of the whole exercise: while one replica holds the lock, no other may sweep. */
    @Test
    void asecondReplicaCannotSweepWhileTheFirstHoldsTheLock() {
        Optional<SimpleLock> held = lockProvider.lock(
                config("concurrentSweep", Duration.ofMinutes(10), Duration.ZERO));
        assertThat(held).isPresent();

        Optional<SimpleLock> contender = lockProvider.lock(
                config("concurrentSweep", Duration.ofMinutes(10), Duration.ZERO));

        assertThat(contender)
                .as("a second sweep must be refused, not merely delayed")
                .isEmpty();

        held.get().unlock();
    }

    /**
     * {@code lockAtLeastFor} is load-bearing rather than cosmetic. Two sightings seconds apart are
     * not independent evidence — money in flight during the first pass is still in flight during
     * the second — so confirming on them defeats the debounce and pages someone for a healthy
     * system. The lock must therefore stay held after an early release.
     */
    @Test
    void aSweepThatFinishesQuicklyStillKeepsTheNextOneAwayForLockAtLeastFor() {
        Optional<SimpleLock> first = lockProvider.lock(
                config("pacedSweep", Duration.ofMinutes(10), Duration.ofMinutes(4)));
        assertThat(first).isPresent();

        first.get().unlock();   // the sweep finished in milliseconds

        assertThat(lockProvider.lock(config("pacedSweep", Duration.ofMinutes(10), Duration.ofMinutes(4))))
                .as("released early, but lockAtLeastFor must still hold the next sweep off")
                .isEmpty();
    }

    /** A lock released past its lockAtLeastFor window is genuinely free again. */
    @Test
    void theLockIsReusableOnceItsMinimumHoldHasPassed() {
        Optional<SimpleLock> first = lockProvider.lock(
                config("sequentialSweep", Duration.ofMinutes(10), Duration.ZERO));
        assertThat(first).isPresent();
        first.get().unlock();

        Optional<SimpleLock> second = lockProvider.lock(
                config("sequentialSweep", Duration.ofMinutes(10), Duration.ZERO));

        assertThat(second).isPresent();
        second.get().unlock();
    }
}
