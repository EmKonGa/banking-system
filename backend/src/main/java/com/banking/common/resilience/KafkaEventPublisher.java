package com.banking.common.resilience;

import com.banking.payment.event.PaymentEvent;
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

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @CircuitBreaker(name = "kafka-producer")
    @Retry(name = "kafka-producer", fallbackMethod = "publishFallback")
    public void publish(String topic, String key, PaymentEvent event) {
        log.info("[KAFKA-PUBLISH] attempt for transaction={}", event.transactionId());
        kafkaTemplate.send(topic, key, event);
    }

    void publishFallback(String topic, String key, PaymentEvent event, Throwable t) {
        log.error("[KAFKA-PUBLISH] all retries exhausted for transaction={}: {}",
                event.transactionId(), t.getMessage());
        // Rethrow so the OutboxPoller knows the publish failed and can track retry count
        if (t instanceof RuntimeException re) throw re;
        throw new RuntimeException("Kafka publish failed: " + t.getMessage(), t);
    }
}
