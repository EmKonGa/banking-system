package com.banking.payment.config;

import com.banking.payment.entity.OutboxEvent;
import com.banking.payment.entity.OutboxStatus;
import com.banking.payment.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gauge that makes an abandoned payment event visible, against a real PostgreSQL.
 *
 * <p>Worth a test rather than being taken on faith, because the failure this guards against is not
 * "the gauge reports a wrong number" — it is "the gauge is present, registered, scraped, and wired
 * to nothing", which is the shape of bug this repo keeps finding (a mounted volume the broker never
 * wrote to; a dead-letter recoverer behind a handler that never threw). A gauge that always reads
 * zero is indistinguishable from a healthy system, and it is the alert that would never fire.
 *
 * <p>So the assertions are about the connection between the meter and the rows, in both directions:
 * it must move when a row is abandoned, and it must <em>not</em> move for the statuses that are
 * normal. The lookup is by metric name through the registry rather than through the injected
 * {@code Gauge} bean, because the name is the part Prometheus and {@code alerts.yml} depend on and
 * the part a rename would silently break.
 *
 * <p>Booting the full context also runs Flyway, so {@code V4__outbox_failed_index.sql} is exercised
 * here rather than first meeting a database on deploy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OutboxAbandonedGaugeTest {

    private static final String METRIC = "payment_outbox_abandoned_events";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO banking_payment");
        registry.add("app.jwt.secret", () -> "test-jwt-secret-that-is-long-enough-for-hs256-256bits");
        registry.add("internal.secret", () -> "test-internal-secret");
        registry.add("management.tracing.enabled", () -> "false");

        // Park both schedulers. @EnableScheduling is on the application class, so booting the full
        // context starts the outbox poller and the recovery poller — and Spring caches this context
        // past the last test in the class, while @Container stops PostgreSQL as soon as the class
        // ends. A poll that fires in that gap blocks for Hikari's full 30s connection-timeout and
        // adds it to the build ("Surefire is going to kill self fork JVM").
        //
        // It also removes a genuine race: one test saves a PENDING row, which is exactly what
        // findPendingWithLock claims, and a poller running concurrently would mutate the row the
        // assertion is about. A fixedDelay task still runs once at startup — harmless, the table is
        // empty then — and never again inside a test's lifetime.
        registry.add("outbox.poll-interval-ms", () -> "3600000");
        registry.add("transfer.recovery.poll-interval-ms", () -> "3600000");
    }

    @Autowired
    OutboxEventRepository outbox;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
    }

    private double abandoned() {
        return meters.get(METRIC).gauge().value();
    }

    private void save(OutboxStatus status) {
        OutboxEvent event = OutboxEvent.of("payment.events", "tx-1", "{}");
        event.setStatus(status);
        outbox.save(event);
    }

    @Test
    void readsZeroWhenNothingHasBeenAbandoned() {
        save(OutboxStatus.PENDING);
        save(OutboxStatus.PUBLISHED);

        // Both of these are ordinary. A gauge that counted them would be noise on every transfer,
        // and the alert has no `for:` clause precisely because the state it reports is terminal.
        assertThat(abandoned()).isZero();
    }

    @Test
    void countsRowsTheOutboxPollerGaveUpOn() {
        save(OutboxStatus.FAILED);
        save(OutboxStatus.FAILED);
        save(OutboxStatus.PUBLISHED);

        assertThat(abandoned()).isEqualTo(2d);
    }

    @Test
    void reReadsTheDatabaseOnEveryScrape() {
        assertThat(abandoned()).isZero();

        save(OutboxStatus.FAILED);

        // The value must come from the rows at scrape time, not from a number captured when the
        // bean was built. Registering a gauge against a snapshot is an easy mistake that this
        // assertion is the only thing to catch — the first read above would still be correct.
        assertThat(abandoned()).isEqualTo(1d);
    }
}
