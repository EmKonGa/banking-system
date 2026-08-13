package com.banking.payment.service;

import com.banking.payment.event.OutboxTriggerEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The trigger hands the publish to another thread, and never runs it on the caller's.
 *
 * <p>This is the assertion the finding was about. {@code KafkaEventPublisher} blocks on the broker
 * ack, so whichever thread invokes a poll wears the whole batch's latency — and while this listener
 * lived on {@code OutboxPoller} that thread was the one serving the user's transfer. The bug was
 * invisible to a passing test suite, because with a healthy broker the publish returns in
 * milliseconds and running it inline looks identical to handing it off. So these tests pin the
 * <em>thread</em> rather than the outcome: it is the only thing that differs until Kafka misbehaves.
 *
 * <p>A real executor rather than a stubbed one, because the queue bound and the rejection policy are
 * the substance of the fix; a mock would only confirm that {@code execute} was called.
 *
 * <p>Latches rather than sleeps or polling, so nothing here is timing-dependent — the one duration
 * asserted on is the caller's, which is the point.
 */
class OutboxTriggerTest {

    private OutboxTrigger trigger;

    /** Names of threads that actually ran a poll, so the caller's can be shown to be absent. */
    private final Set<String> pollThreads = ConcurrentHashMap.newKeySet();
    private final AtomicInteger polls = new AtomicInteger();

    private CountDownLatch started;
    private CountDownLatch release;

    @BeforeEach
    void setUp() {
        started = new CountDownLatch(1);
        release = new CountDownLatch(0);

        OutboxPoller poller = mock(OutboxPoller.class);
        Mockito.doAnswer(invocation -> {
            pollThreads.add(Thread.currentThread().getName());
            polls.incrementAndGet();
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(poller).pollAndPublish();

        trigger = new OutboxTrigger(poller);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        trigger.shutdown();
    }

    @Test
    void runsThePublishOffTheCallingThread() throws Exception {
        String caller = Thread.currentThread().getName();

        trigger.onTransferCommitted(new OutboxTriggerEvent());

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pollThreads).doesNotContain(caller);
        assertThat(pollThreads).allSatisfy(name -> assertThat(name).startsWith("outbox-publish-"));
    }

    @Test
    void returnsWithoutWaitingForThePublishToFinish() throws Exception {
        release = new CountDownLatch(1);

        long startedAt = System.nanoTime();
        trigger.onTransferCommitted(new OutboxTriggerEvent());
        long callerBlockedMs = (System.nanoTime() - startedAt) / 1_000_000;

        // The poll is inside the fake and still blocked on `release` at this point. Before the fix
        // the call above would not have returned until it finished — for a real batch under a Kafka
        // outage, minutes, on the thread serving POST /api/payments/transfer.
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(callerBlockedMs).isLessThan(1000);
    }

    @Test
    void dropsExtraTriggersInsteadOfRunningThemOnTheCaller() throws Exception {
        release = new CountDownLatch(1);
        String caller = Thread.currentThread().getName();

        // The first occupies the worker, the second fills the queue, the rest have nowhere to go.
        // The rejection handler has to swallow them: CallerRunsPolicy would run a blocking publish
        // right here, and the default AbortPolicy would throw a TaskRejectedException out of an
        // AFTER_COMMIT callback for a transaction that has already committed.
        for (int i = 0; i < 10; i++) {
            trigger.onTransferCommitted(new OutboxTriggerEvent());
        }

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pollThreads).doesNotContain(caller);

        // Drain deterministically: shutdown waits for the running task and the queued one.
        release.countDown();
        trigger.shutdown();

        // One that ran plus at most one that was queued. Dropping the other eight costs nothing —
        // a poll publishes every PENDING row, so the queued one already covers what they asked for.
        assertThat(polls.get()).isBetween(1, 2);
    }
}
