package com.banking.payment.resilience;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @CircuitBreaker(name = "kafka-producer")
    @Retry(name = "kafka-producer", fallbackMethod = "publishFallback")
    public void publish(String topic, String key, Object event) {
        log.info("[KAFKA-PUBLISH] attempt for key={}", key);
        kafkaTemplate.send(topic, key, event);
    }

    void publishFallback(String topic, String key, Object event, Throwable t) {
        log.error("[KAFKA-PUBLISH] all retries exhausted for key={}: {}", key, t.getMessage());
        if (t instanceof RuntimeException re) throw re;
        throw new RuntimeException("Kafka publish failed: " + t.getMessage(), t);
    }
}
