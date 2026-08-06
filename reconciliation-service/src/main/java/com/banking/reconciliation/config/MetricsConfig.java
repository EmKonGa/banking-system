package com.banking.reconciliation.config;

import com.banking.reconciliation.entity.FindingStatus;
import com.banking.reconciliation.entity.SweepState;
import com.banking.reconciliation.repository.ReconciliationFindingRepository;
import com.banking.reconciliation.repository.SweepStateRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    /**
     * The number to alert on. It counts CONFIRMED findings only — a SUSPECTED one is very often a
     * transfer that was in flight while the sweep read the two sides, and paging on those would
     * train whoever carries the pager to ignore the alert.
     *
     * <p>Read from the database rather than from the last sweep's return value so it survives a
     * restart: a confirmed discrepancy does not stop existing because the auditor was redeployed.
     */
    @Bean
    public Gauge confirmedFindingsGauge(MeterRegistry registry,
                                        ReconciliationFindingRepository findings) {
        return Gauge.builder("reconciliation_confirmed_findings",
                        () -> findings.countByStatus(FindingStatus.CONFIRMED))
                .description("Discrepancies between account balances and the ledger, seen on two or more consecutive sweeps")
                .register(registry);
    }

    /**
     * Registered separately rather than as a tag on the gauge above: alerting wants one number, and
     * a tagged series would make the obvious alert expression sum both severities together.
     */
    @Bean
    public Gauge suspectedFindingsGauge(MeterRegistry registry,
                                        ReconciliationFindingRepository findings) {
        return Gauge.builder("reconciliation_suspected_findings",
                        () -> findings.countByStatus(FindingStatus.SUSPECTED))
                .description("Discrepancies seen exactly once — usually money in flight, not breakage")
                .register(registry);
    }

    /**
     * Whether the auditor is still auditing. Alert on staleness:
     *
     * <pre>time() - reconciliation_last_successful_sweep_timestamp_seconds &gt; 1800</pre>
     *
     * <p>This is the one that has to exist, because the two gauges above cannot fall to a bad value
     * on their own — they are read from the register, so when sweeps stop they simply hold whatever
     * was last true. A clean register plus a dead sweeper reads exactly like a healthy system, and
     * every way this service fails produces that state: the key-set ceiling, account-service being
     * unreachable, a bad deploy, an unhandled bug in a sweep.
     *
     * <p>Nothing else watches the sweeper. It is the check {@code STUCK_INTENT} performs for the
     * transfer recovery poller, turned back on the auditor itself.
     *
     * <p>Zero when no sweep has ever completed — infinitely stale, which alerts. That is the honest
     * reading for a service that has not yet done its job, including for the minute after a deploy.
     */
    @Bean
    public Gauge lastSuccessfulSweepGauge(MeterRegistry registry, SweepStateRepository sweepState) {
        return Gauge.builder("reconciliation_last_successful_sweep_timestamp_seconds",
                        () -> sweepState.findById(SweepState.SINGLETON_ID)
                                .map(state -> (double) state.getLastSuccessfulSweepAt().getEpochSecond())
                                .orElse(0d))
                .description("Unix time of the last sweep that completed; stale means the auditor has stopped")
                .register(registry);
    }
}
