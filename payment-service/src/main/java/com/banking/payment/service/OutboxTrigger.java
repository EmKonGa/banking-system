package com.banking.payment.service;

import com.banking.payment.event.OutboxTriggerEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Wakes the outbox publisher once a settlement has committed, without waiting for it.
 *
 * <p>{@code KafkaEventPublisher.publish} blocks until the broker acknowledges — deliberately, that
 * is what stopped the outbox marking rows PUBLISHED for messages it had merely buffered. The cost of
 * that was never traced. This listener used to live on {@link OutboxPoller} and call
 * {@code pollAndPublish()} directly, and {@code @TransactionalEventListener} runs
 * <strong>synchronously on the committing thread</strong>: for a transfer that is the thread serving
 * {@code POST /api/payments/transfer}, and the poll it triggered was a batch of up to ten rows, each
 * bounded by {@code delivery.timeout.ms}. A transfer whose money had already moved could hold its
 * HTTP response for minutes, and api-gateway sets no response timeout, so that reached the browser.
 *
 * <p><strong>A separate bean from {@link OutboxPoller}, for the reason {@link TransferLedger} is
 * separate from {@link PaymentService}:</strong> {@code @Transactional} only applies through a
 * proxy. Handing {@code this::pollAndPublish} to an executor from inside {@code OutboxPoller} would
 * be a self-invocation — the executor thread would call the raw method and the poll would run with
 * <em>no transaction at all</em>, so {@code FOR UPDATE SKIP LOCKED} would claim nothing and two
 * publishers could take the same batch. Injecting the bean means the call goes through the proxy.
 *
 * <p>Delivery does not depend on any of this. The row is already committed and the 15s
 * {@code @Scheduled} poll publishes it regardless; the trigger only decides whether the recipient's
 * notification arrives in milliseconds or in seconds.
 */
@Slf4j
@Component
public class OutboxTrigger {

    private final OutboxPoller outboxPoller;
    private final ThreadPoolTaskExecutor executor;

    public OutboxTrigger(OutboxPoller outboxPoller) {
        this.outboxPoller = outboxPoller;
        this.executor = publishExecutor();
    }

    /**
     * Still AFTER_COMMIT, and still for the original reason: an event must not be published for a
     * settlement whose ledger row failed to commit. What changed is that the listener hands the work
     * off instead of doing it.
     *
     * <p>No {@code fallbackExecution}: the only publisher of this event is
     * {@code TransferLedger.settleCompleted}, which is always transactional, so there is no
     * non-transactional path for it to arrive on. Unlike account-service's cache evictor — where
     * missing the callback leaves a stale entry indefinitely — missing this one costs 15 seconds.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferCommitted(OutboxTriggerEvent event) {
        executor.execute(outboxPoller::pollAndPublish);
    }

    /**
     * One thread, a queue of one, and a discard on overflow.
     *
     * <p><strong>One thread</strong> because concurrent polls are safe but pointless:
     * {@code findPendingWithLock} claims its batch {@code FOR UPDATE SKIP LOCKED}, so a second
     * publisher takes a different batch rather than the same one — but under the Kafka outage that
     * makes any of this matter, every extra thread is another 30s block, and an unbounded pool would
     * grow one per transfer.
     *
     * <p><strong>A queue of one, discarding the rest</strong>, because the trigger is a hint rather
     * than a delivery mechanism. What it asks for is not "publish this row" but "publish whatever is
     * PENDING", so a poll already running or already queued will pick up a row committed after it
     * started. Past one pending wake-up, further triggers ask for work that is provably scheduled.
     * The discard is logged rather than silent, but during an outage it is expected traffic, not an
     * error — which is why it is debug.
     *
     * <p>{@code CallerRunsPolicy} would be the wrong choice for precisely the reason this class
     * exists: it hands the work straight back to the request thread being protected.
     *
     * <p>⚠️ <strong>Built here rather than exposed as an {@code @Bean}, and that is not a style
     * choice.</strong> Spring Boot's {@code TaskExecutorConfiguration} is
     * {@code @ConditionalOnMissingBean(Executor.class)}, so publishing <em>any</em> {@code Executor}
     * bean deletes {@code applicationTaskExecutor} from the context. This one would then be the only
     * {@code AsyncTaskExecutor} present, and Spring MVC's async support — plus any future
     * {@code @Async} — would silently run on a single thread that blocks for 30s per Kafka ack. That
     * is the same bug this class was written to fix, one layer up: a pool shared by things that do
     * not know they share it.
     */
    private ThreadPoolTaskExecutor publishExecutor() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(1);
        pool.setThreadNamePrefix("outbox-publish-");
        pool.setRejectedExecutionHandler((task, ignored) ->
                log.debug("[OUTBOX] a publish is running with one wake-up queued — dropping this trigger"));

        // Let an in-flight publish finish rather than interrupting it. Not for delivery — the row
        // stays PENDING either way and the consumer dedupes on processed_events — but an interrupt
        // surfaces as a failed publish, and OutboxPoller counts failures toward outbox.max-retries.
        // Without this, a rolling restart spends part of the five-attempt budget that exists for
        // broker failures, and payment_outbox_abandoned_events would eventually move because of a
        // deploy rather than because of an outage.
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(10);
        pool.initialize();
        return pool;
    }

    /** Constructed by hand, so it is shut down by hand — Spring only disposes beans it created. */
    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
