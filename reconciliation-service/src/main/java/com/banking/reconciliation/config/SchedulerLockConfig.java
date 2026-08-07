package com.banking.reconciliation.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Makes {@code ReconciliationSweeper.sweep} a singleton across replicas.
 *
 * <p>Every other service in this stack has been made replica-safe, which makes this the one that
 * gets scaled without a second thought — and it is the one where concurrency is silent. See
 * {@code V3__shedlock.sql} for what two overlapping sweeps do to the register.
 *
 * <p>{@code defaultLockAtMostFor} is the backstop for a node that dies holding the lock: nothing
 * else can sweep until it expires. It is kept well inside the 1800s staleness alert on
 * {@code reconciliation_last_successful_sweep_timestamp_seconds}, so a crash costs one or two
 * sweeps rather than paging someone.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "${reconciliation.lock-at-most-for:PT10M}")
public class SchedulerLockConfig {

    /**
     * The table name is schema-qualified deliberately. ShedLock issues plain JDBC, not JPA, so
     * {@code hibernate.default_schema} does not apply to it — it resolves against the connection's
     * {@code search_path}, which in production comes from {@code ?currentSchema=} in the JDBC URL
     * and which a Testcontainers {@code @ServiceConnection} replaces. That is the same trap the
     * native ledger aggregate hit in payment-service; qualifying the name removes the dependency
     * on connection state entirely rather than restoring it per-test.
     *
     * <p>Sourced from the Flyway schema property so the table cannot drift away from the migration
     * that creates it.
     */
    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate,
                                     @Value("${spring.flyway.default-schema}") String schema) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .withTableName(schema + ".shedlock")
                        // Postgres stores TIMESTAMPTZ, so let the database stamp the clock rather
                        // than each replica stamping its own. Lock expiry compared across nodes
                        // with drifting clocks is how two holders happen.
                        .usingDbTime()
                        .build());
    }
}
