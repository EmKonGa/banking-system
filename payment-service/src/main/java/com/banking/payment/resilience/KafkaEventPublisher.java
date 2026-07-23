package com.banking.payment.resilience;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes an outbox row and waits for the broker to acknowledge it. {@code send} returns once the
 * record is buffered, not when a broker accepts it, so discarding the future let {@code
 * OutboxPoller} mark rows PUBLISHED for messages that were never delivered.
 */
@Slf4j
@Component
public class KafkaEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final long ackTimeoutMs;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                               @Value("${kafka.publish.ack-timeout-ms:35000}") long ackTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.ackTimeoutMs = ackTimeoutMs;
    }

    /**
     * No {@code @Retry}: the producer has already exhausted its internal retries over
     * {@code delivery.timeout.ms} by the time this returns, so an outer retry is a fresh send that
     * turns a 30s wait into 90s while the poller holds its row locks. The outbox is the retry layer.
     */
    @CircuitBreaker(name = "kafka-producer")
    public void publish(String topic, String key, Object event) {
        log.info("[KAFKA-PUBLISH] attempt for key={}", key);
        try {
            kafkaTemplate.send(topic, key, event).get(ackTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // Unwrap: record-exceptions is a whitelist of Kafka types and matches no wrapper, so
            // rethrowing as-is would have the breaker score a failed publish as a success.
            throw asKafkaFailure(e.getCause(), key);
        } catch (TimeoutException e) {
            throw new org.springframework.kafka.KafkaException(
                    "Timed out after " + ackTimeoutMs + "ms awaiting Kafka ack for key=" + key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new org.springframework.kafka.KafkaException(
                    "Interrupted awaiting Kafka ack for key=" + key, e);
        }
    }

    /** Keeps the broker's own type where possible; wraps anything else in one the whitelist covers. */
    private RuntimeException asKafkaFailure(Throwable cause, String key) {
        if (cause instanceof KafkaException ke) return ke;
        return new org.springframework.kafka.KafkaException("Kafka publish failed for key=" + key, cause);
    }
}
