package com.banking.notification.config;

import com.banking.common.exception.AppException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    /**
     * Publishes to {@code <topic>.DLT} once retries are exhausted, and counts what it publishes.
     *
     * <p>This only became reachable when {@code PaymentEventConsumer} stopped swallowing its own
     * exceptions. Wiring a dead-letter recoverer behind a handler that never sees a failure changes
     * nothing — the messages were being dropped inside the listener, not after it.
     *
     * <p>Anything landing in {@code payment.events.DLT} means a user was not told about a transfer
     * that did happen. That was stated here for some time as something "worth alerting on" while
     * nothing did: the topic retained the evidence and no metric, dashboard or rule ever read it. A
     * dead-letter destination nobody watches is a quieter failure than no dead-letter destination at
     * all, because the mechanism looks present in review.
     *
     * @see #countingRecoverer to understand why the counter increments where it does
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> dltKafkaTemplate,
                                                 MeterRegistry registry) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltKafkaTemplate);

        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxAttempts(3);
        backOff.setMaxInterval(10_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(countingRecoverer(recoverer, registry), backOff);
        // A business rejection will fail identically on every attempt; retrying only delays the DLT.
        errorHandler.addNotRetryableExceptions(AppException.class);
        errorHandler.setRetryListeners((record, ex, attempt) ->
                log.warn("[KAFKA-RETRY] attempt {} for topic={} key={}: {}",
                        attempt, record.topic(), record.key(), ex.getMessage()));
        return errorHandler;
    }

    /**
     * Wraps the recoverer so every dead-lettered record moves a counter.
     *
     * <p><strong>The increment is after {@code accept} returns, and that ordering is the whole
     * point.</strong> Counting first would report a message as safely parked at the moment it was
     * handed to a producer — the same mistake the outbox made when it marked rows PUBLISHED from a
     * discarded {@code send()} future, and it would be worse here, because the number exists
     * precisely to be trusted when someone asks "did we lose anything?".
     *
     * <p>It is safe to rely on: {@code DeadLetterPublishingRecoverer} defaults to
     * {@code failIfSendResultIsError = true} and blocks in {@code verifySendResult} until the broker
     * answers, so a return means the record is on the topic and a publish failure throws — which
     * leaves the counter untouched and lets the container retry, exactly as it should.
     *
     * <p>Registered per pod, and notification-service runs 2 replicas, so alert on
     * {@code sum(...)} across instances rather than on a single series.
     */
    private ConsumerRecordRecoverer countingRecoverer(DeadLetterPublishingRecoverer recoverer,
                                                      MeterRegistry registry) {
        Counter deadLettered = Counter.builder("notification_events_dead_lettered")
                .description("Payment events parked on the dead-letter topic; each one is a user not told about a transfer that happened")
                .register(registry);

        return (record, exception) -> {
            recoverer.accept(record, exception);
            deadLettered.increment();
        };
    }

    /**
     * A dedicated template for the DLT, because the two things it has to publish are not the same
     * shape. A handler failure republishes the deserialized {@code PaymentEvent} (JSON), while a
     * <em>deserialization</em> failure has no object to speak of and republishes the original
     * {@code byte[]} — which JsonSerializer would re-encode as a base64 string, making the
     * dead-lettered payload useless for replay. Delegating by type keeps the raw bytes raw.
     */
    @Bean
    public KafkaTemplate<Object, Object> dltKafkaTemplate(KafkaProperties properties) {
        ProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(null),
                new JsonSerializer<>(),
                new DelegatingByTypeSerializer(Map.of(
                        byte[].class, new ByteArraySerializer(),
                        Object.class, new JsonSerializer<>())));
        return new KafkaTemplate<>(factory);
    }
}
