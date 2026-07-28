package com.banking.notification.config;

import com.banking.common.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
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
     * Publishes to {@code <topic>.DLT} once retries are exhausted.
     *
     * <p>This only became reachable when {@code PaymentEventConsumer} stopped swallowing its own
     * exceptions. Wiring a dead-letter recoverer behind a handler that never sees a failure changes
     * nothing — the messages were being dropped inside the listener, not after it.
     *
     * <p>Anything landing in {@code payment.events.DLT} means a user was not told about a transfer
     * that did happen, so it is worth alerting on rather than merely retaining.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> dltKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltKafkaTemplate);

        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxAttempts(3);
        backOff.setMaxInterval(10_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // A business rejection will fail identically on every attempt; retrying only delays the DLT.
        errorHandler.addNotRetryableExceptions(AppException.class);
        errorHandler.setRetryListeners((record, ex, attempt) ->
                log.warn("[KAFKA-RETRY] attempt {} for topic={} key={}: {}",
                        attempt, record.topic(), record.key(), ex.getMessage()));
        return errorHandler;
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
