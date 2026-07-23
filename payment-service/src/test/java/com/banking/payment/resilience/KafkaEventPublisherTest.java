package com.banking.payment.resilience;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The outbox's at-least-once guarantee rests entirely on a failed publish being visible to its
 * caller. {@code KafkaTemplate.send} completes as soon as the record is buffered, so a publisher
 * that ignores the returned future reports success for a message no broker ever accepted — and
 * {@code OutboxPoller} then marks the row PUBLISHED and moves on. Every test here fails against
 * that earlier fire-and-forget implementation.
 */
@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    private static final String TOPIC = "payment.events";
    private static final String KEY = "tx-123";
    private static final Object EVENT = new Object();

    private KafkaEventPublisher publisherWithTimeout(long ackTimeoutMs) {
        return new KafkaEventPublisher(kafkaTemplate, ackTimeoutMs);
    }

    private void stubSend(CompletableFuture<SendResult<String, Object>> future) {
        when(kafkaTemplate.send(eq(TOPIC), eq(KEY), any())).thenReturn(future);
    }

    @Test
    void acknowledgedSendReturnsNormally() {
        SendResult<String, Object> result = new SendResult<>(null,
                new RecordMetadata(new TopicPartition(TOPIC, 0), 0, 0, 0L, 0, 0));
        stubSend(CompletableFuture.completedFuture(result));

        assertThatCode(() -> publisherWithTimeout(1_000).publish(TOPIC, KEY, EVENT))
                .doesNotThrowAnyException();
    }

    /**
     * The core regression. A broker rejection arrives asynchronously, so it is only observable by
     * awaiting the future — the old implementation returned normally here and the event was lost
     * while the outbox recorded success.
     */
    @Test
    void brokerRejectionSurfacesToTheCaller() {
        stubSend(CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.TimeoutException("no leader for partition")));

        assertThatThrownBy(() -> publisherWithTimeout(1_000).publish(TOPIC, KEY, EVENT))
                .isInstanceOf(org.apache.kafka.common.errors.TimeoutException.class);
    }

    /**
     * The cause must be unwrapped, not rethrown inside its {@code ExecutionException}. The
     * breaker's {@code record-exceptions} is a whitelist of Kafka types: a wrapper matches none of
     * them, so the breaker would score a failed publish as a success and never open — the same
     * class of bug this change removes.
     */
    @Test
    void failureIsUnwrappedToAKafkaTypeTheBreakerWhitelistCanMatch() {
        stubSend(CompletableFuture.failedFuture(new RecordTooLargeException("payload too big")));

        assertThatThrownBy(() -> publisherWithTimeout(1_000).publish(TOPIC, KEY, EVENT))
                .isInstanceOf(org.apache.kafka.common.KafkaException.class)
                .isNotInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    /**
     * A producer that never completes its future must not pin the poller's transaction open
     * indefinitely — the poller holds row locks for the batch it claimed.
     */
    @Test
    void aSendThatNeverCompletesTimesOutRatherThanBlockingForever() {
        stubSend(new CompletableFuture<>()); // never completes

        long start = System.nanoTime();
        assertThatThrownBy(() -> publisherWithTimeout(150).publish(TOPIC, KEY, EVENT))
                .isInstanceOf(org.springframework.kafka.KafkaException.class)
                .hasMessageContaining("Timed out");

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).as("must give up near the configured timeout").isLessThan(5_000);
    }
}
