package com.banking.payment.config;

import com.banking.payment.entity.OutboxStatus;
import com.banking.payment.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    /**
     * How many outbox rows the poller has given up on.
     *
     * <p>{@code OutboxPoller} sets {@code status = FAILED} once a row has failed
     * {@code outbox.max-retries} times, and {@code findPendingWithLock} selects {@code PENDING}
     * only — so a FAILED row is <strong>terminal</strong>. Nothing retries it, nothing replays it,
     * and until this gauge existed nothing counted it either: the loss was a single
     * {@code log.error} line in a pod's stdout.
     *
     * <p><strong>Reconciliation cannot cover this, and is right not to.</strong> The money moved,
     * the ledger row says COMPLETED and the balance matches, so a sweep reports the system clean.
     * What was lost is the notification — the user was never told about a transfer that did happen.
     * That is precisely the shape of failure the reconciler is built to be blind to, which is why it
     * needs its own number.
     *
     * <p>Read from the database rather than counted in memory, for the same reason
     * {@code reconciliation_confirmed_findings} is: an abandoned event does not stop existing
     * because the pod was redeployed, and a counter would reset to zero at exactly the moment
     * someone is redeploying to fix it.
     *
     * <p>Note what this number does <em>not</em> tell you: it moves after the loss, not before.
     * Roughly 450 seconds of Kafka being unavailable is enough to exhaust the backoff schedule
     * (30s → 60s → 120s → 240s), and there is no metric that fires while that clock is running.
     */
    @Bean
    public Gauge abandonedOutboxEventsGauge(MeterRegistry registry, OutboxEventRepository outbox) {
        return Gauge.builder("payment_outbox_abandoned_events",
                        () -> outbox.countByStatus(OutboxStatus.FAILED))
                .description("Outbox rows the poller gave up publishing; each one is a payment event that will never be delivered")
                .register(registry);
    }
}
